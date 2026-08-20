package com.checkba.service.mobile;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 桌面侧客户端对着**真 HTTP 桩**跑完整编排（不 mock 传输层）：
 * - 首次出站自动用 awdk_ 桥接换 awdt_ 并持久化；
 * - 目录推送带 deviceId 与项目清单；同清单第二轮不重复出站；
 * - 取件：下载内容 → 建「现场影像/日期」两级目录 → createFile + 写字节 → ACK；
 * - 同名已落盘（上轮 ACK 丢失）时不重复建文件，直接补 ACK；
 * - 项目不存在时留置（不 ACK，交给云端 TTL）。
 */
class MobileRelayClientHttpTest {

    private static final String MEDIA_ID = "0a1b2c3d-1111-4222-8333-444455556666";

    private HttpServer server;
    private String baseUrl;

    private AccountService accountService;
    private LocalIdentityService localIdentityService;
    private ProjectRepository projectRepository;
    private ProjectFileService projectFileService;
    private StorageServiceFactory storageServiceFactory;
    private StorageService storageService;

    @TempDir
    Path stateDir;

    private final List<String> bridgeBodies = new CopyOnWriteArrayList<>();
    private final List<String> dirBodies = new CopyOnWriteArrayList<>();
    private final List<String> acked = new CopyOnWriteArrayList<>();
    private volatile String inboxJson = "[]";

    // 假文件树：parentId+name → ProjectFile
    private final List<ProjectFile> tree = new ArrayList<>();
    private final AtomicLong fileSeq = new AtomicLong(100);
    private final List<String> savedStorageKeys = new ArrayList<>();
    private final List<byte[]> savedBytes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/awdk-login", ex -> {
            bridgeBodies.add(readBody(ex.getRequestBody()));
            respond(ex, 200, "{\"code\":0,\"data\":{\"token\":\"awdt_test_token\",\"userId\":3,\"username\":\"awd_x\"}}");
        });
        server.createContext("/api/mobile/projects", ex -> {
            dirBodies.add(readBody(ex.getRequestBody()));
            respond(ex, 200, "{\"code\":0}");
        });
        server.createContext("/api/mobile/inbox", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/content")) {
                respond(ex, 200, "JPEG-BYTES");
            } else if (path.endsWith("/ack")) {
                acked.add(path);
                respond(ex, 200, "{\"code\":0}");
            } else {
                respond(ex, 200, inboxJson);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        accountService = mock(AccountService.class);
        when(accountService.currentKeyOrNull()).thenReturn("awdk_test_key");
        when(accountService.accountFingerprintOrNull()).thenReturn("fp-1234");

        localIdentityService = mock(LocalIdentityService.class);
        when(localIdentityService.localUserId()).thenReturn(7L);

        projectRepository = mock(ProjectRepository.class);
        Project p42 = new Project();
        p42.setId(42L);
        p42.setName("金冠纾困");
        p42.setUserId(7L);
        when(projectRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(p42));
        when(projectRepository.findById(42L)).thenReturn(Optional.of(p42));
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());

        projectFileService = mock(ProjectFileService.class);
        when(projectFileService.getFilesByParent(anyLong(), any()))
                .thenAnswer(inv -> {
                    Long parent = inv.getArgument(1);
                    List<ProjectFile> out = new ArrayList<>();
                    for (ProjectFile f : tree) {
                        if (parent == null ? f.getParentId() == null : parent.equals(f.getParentId())) out.add(f);
                    }
                    return out;
                });
        when(projectFileService.createFolder(anyLong(), any(), anyString(), anyLong()))
                .thenAnswer(inv -> addNode(inv.getArgument(1), inv.getArgument(2), true));
        when(projectFileService.createFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    ProjectFile f = addNode(inv.getArgument(1), inv.getArgument(2), false);
                    f.setFilePath("projects/42/" + f.getName());
                    return f;
                });

        storageService = mock(StorageService.class);
        when(storageService.save(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            savedStorageKeys.add(inv.getArgument(0));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            inv.getArgument(1, InputStream.class).transferTo(buf);
            savedBytes.add(buf.toByteArray());
            return inv.getArgument(0);
        });
        storageServiceFactory = mock(StorageServiceFactory.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ProjectFile addNode(Long parentId, String name, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(fileSeq.getAndIncrement());
        f.setProjectId(42L);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        tree.add(f);
        return f;
    }

    private MobileRelayClientService service() {
        return new MobileRelayClientService(true, true, baseUrl,
                "https://www.aiworkdeck.com", stateDir.toString(),
                accountService, localIdentityService, projectRepository,
                projectFileService, storageServiceFactory);
    }

    private static String readBody(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    @Test
    @DisplayName("目录推送：首次出站先桥接，请求带 deviceId 与项目清单；同清单第二轮不重复出站")
    void pushDirectoryBridgesAndDedupes() {
        MobileRelayClientService svc = service();
        svc.pushDirectory();

        assertEquals(1, bridgeBodies.size(), "应自动桥接一次");
        assertTrue(bridgeBodies.get(0).contains("awdk_test_key"));
        assertEquals(1, dirBodies.size());
        assertTrue(dirBodies.get(0).contains("\"key\":\"42\""));
        assertTrue(dirBodies.get(0).contains("金冠纾困"));
        assertTrue(dirBodies.get(0).contains(svc.deviceId()));

        svc.pushDirectory();
        assertEquals(1, dirBodies.size(), "清单没变不该重复出站");
        assertEquals(1, bridgeBodies.size(), "令牌已持久化，不该重复桥接");
    }

    @Test
    @DisplayName("取件全链路：下载→现场影像/日期两级目录→createFile+写字节→ACK")
    void pollInboxLandsAndAcks() {
        inboxJson = "[{\"id\":9,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"IMG_0001.jpg\",\"mediaType\":\"image\",\"fileSize\":10,"
                + "\"capturedAt\":\"2026-08-19T21:00:00\",\"createdAt\":\"2026-08-20T09:00:00\"}]";
        service().pollInbox();

        assertTrue(tree.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && "现场影像".equals(f.getName())));
        assertTrue(tree.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && "2026-08-19".equals(f.getName())));
        assertTrue(tree.stream().anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder())
                && "IMG_0001-0a1b2c3d.jpg".equals(f.getName())));
        assertEquals(1, savedBytes.size());
        assertEquals("JPEG-BYTES", new String(savedBytes.get(0), StandardCharsets.UTF_8));
        assertEquals(List.of("/api/mobile/inbox/9/ack"), acked);
    }

    @Test
    @DisplayName("幂等：同名已落盘（上轮 ACK 丢失）不重复建文件，直接补 ACK")
    void pollInboxSkipsAlreadyLanded() {
        ProjectFile root = addNode(null, "现场影像", true);
        ProjectFile day = addNode(root.getId(), "2026-08-19", true);
        addNode(day.getId(), "IMG_0001-0a1b2c3d.jpg", false);

        inboxJson = "[{\"id\":9,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"IMG_0001.jpg\",\"mediaType\":\"image\",\"fileSize\":10,"
                + "\"capturedAt\":\"2026-08-19T21:00:00\"}]";
        service().pollInbox();

        assertTrue(savedBytes.isEmpty(), "不该再写一份字节");
        assertEquals(List.of("/api/mobile/inbox/9/ack"), acked, "但必须补 ACK");
    }

    @Test
    @DisplayName("项目不存在：留置不 ACK（交给云端 7 天 TTL）")
    void pollInboxLeavesOrphanItems() {
        inboxJson = "[{\"id\":9,\"projectKey\":\"999\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"IMG_0001.jpg\",\"mediaType\":\"image\",\"fileSize\":10}]";
        service().pollInbox();
        assertTrue(acked.isEmpty());
        assertTrue(savedBytes.isEmpty());
    }

    @Test
    @DisplayName("账户未连接 / 非 local-mode：一条出站都不发")
    void inactiveWhenNoAccountOrNotLocalMode() {
        when(accountService.currentKeyOrNull()).thenReturn(null);
        service().pushDirectory();
        service().pollInbox();
        assertTrue(bridgeBodies.isEmpty());
        assertTrue(dirBodies.isEmpty());

        when(accountService.currentKeyOrNull()).thenReturn("awdk_test_key");
        MobileRelayClientService notLocal = new MobileRelayClientService(true, false, baseUrl,
                "https://www.aiworkdeck.com", stateDir.toString(),
                accountService, localIdentityService, projectRepository,
                projectFileService, storageServiceFactory);
        notLocal.pushDirectory();
        assertTrue(dirBodies.isEmpty(), "非 local-mode（云端/团队服务器）绝不出站");
    }
}
