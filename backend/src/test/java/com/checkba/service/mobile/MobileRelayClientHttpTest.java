package com.checkba.service.mobile;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountService;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
    // 目录推送响应体：默认与旧行为一致（普通成功，无截断），尽调 P3#5 的截断告警用例改它。
    private volatile String projectsResponseBody = "{\"code\":0}";

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
            respond(ex, 200, projectsResponseBody);
        });
        server.createContext("/api/mobile/inbox", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/content")) {
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
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

    /**
     * 尽调模块 P3 稳定性余项 #5（dev-board#100）：服务端目录条数超上限时不再整批拒绝，
     * 改成截断 + 在响应体里带 truncated/count 字段。桌面端必须把这件事吼出来（WARN 级
     * 日志、点名总数/已同步数），不能让"HTTP 200 = 全部同步成功"的默认假设吞掉「其实
     * 只同步了一部分」——旧代码整批拒绝时至少会在 log.warn 里带上 status/body，本条要
     * 核验新的"部分成功"响应形态下告警同样不会被静默吃掉。
     */
    @Test
    @DisplayName("目录推送被服务端截断：必须 WARN 级明确告警总数与已同步数，不能当普通成功悄悄放过")
    void pushDirectoryTruncationIsLoudlyWarned() {
        projectsResponseBody = "{\"code\":0,\"count\":1000,\"totalCount\":1005,\"truncated\":true}";
        MobileRelayClientService svc = service();

        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MobileRelayClientService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            svc.pushDirectory();
        } finally {
            logbackLogger.detachAppender(appender);
        }

        assertEquals(1, dirBodies.size(), "推送本身仍要正常发出去（截断是服务端的事，不是客户端拒绝推送）");
        boolean warnedWithNumbers = appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("1005")
                        && e.getFormattedMessage().contains("1000"));
        assertTrue(warnedWithNumbers, "截断必须有一条 WARN 日志点名总数(1005)与已同步数(1000)，实际日志：" +
                appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining(" | ")));
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
    @DisplayName("落盘失败（save 抛 IOException）：不留孤行且不误 ACK，下一轮重新下载而不是直接补 ACK")
    void pollInboxFailedSaveLeavesNoOrphanRowAndRedownloadsNextRound() {
        inboxJson = "[{\"id\":9,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"IMG_0001.jpg\",\"mediaType\":\"image\",\"fileSize\":10,"
                + "\"capturedAt\":\"2026-08-19T21:00:00\"}]";

        AtomicInteger saveCalls = new AtomicInteger();
        when(storageService.save(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            InputStream in = inv.getArgument(1, InputStream.class);
            if (saveCalls.getAndIncrement() == 0) {
                // 模拟落盘中途失败（磁盘满/网络中断），底层 IOException 包成 StorageException 冒出来
                in.close();
                throw new StorageException("模拟磁盘写入失败", new IOException("disk full"));
            }
            savedStorageKeys.add(inv.getArgument(0));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            savedBytes.add(buf.toByteArray());
            return inv.getArgument(0);
        });

        // 第一轮：落盘失败
        service().pollInbox();

        assertTrue(tree.stream().noneMatch(f -> !Boolean.TRUE.equals(f.getIsFolder())
                        && f.getName() != null && f.getName().startsWith("IMG_0001")),
                "落盘失败后不该留一条指向空壳的 project_file 记录");
        assertTrue(savedBytes.isEmpty(), "失败这次不该被记成保存成功");
        assertTrue(acked.isEmpty(), "字节都没落好就不许 ACK，否则云端会删原件、影像永久丢失");

        // 第二轮：必须重新下载（不能因为第一轮留下的痕迹被幂等判据当成"已完成"直接补 ACK）
        service().pollInbox();

        assertEquals(1, savedBytes.size(), "第二轮必须重新下载并落盘一次");
        assertEquals("JPEG-BYTES", new String(savedBytes.get(0), StandardCharsets.UTF_8));
        assertEquals(List.of("/api/mobile/inbox/9/ack"), acked, "这次真落好了才允许 ACK");
        assertTrue(tree.stream().anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder())
                && "IMG_0001-0a1b2c3d.jpg".equals(f.getName())));
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
    @DisplayName("令牌失效（HTTP 200 + code 4010 信封）：作废重桥接并重试，不把拒绝当成功")
    void staleTokenEnvelopeTriggersRebridge() throws Exception {
        // 预置一个「旧令牌」state：桥接过、指纹一致，authed 会直接带它出站
        java.nio.file.Files.writeString(stateDir.resolve("mobile-relay.json"),
                "{\"deviceId\":\"dev-persisted\",\"token\":\"awdt_stale\",\"accountFingerprint\":\"fp-1234\"}");

        // 目录端点：旧令牌回 4010 信封（HTTP 200），新令牌才收下
        server.removeContext("/api/mobile/projects");
        server.createContext("/api/mobile/projects", ex -> {
            String sid = ex.getRequestHeaders().getFirst("X-Session-Id");
            dirBodies.add(readBody(ex.getRequestBody()));
            if ("awdt_stale".equals(sid)) {
                respond(ex, 200, "{\"code\":4010,\"message\":\"请先登录\"}");
            } else {
                respond(ex, 200, "{\"code\":0}");
            }
        });

        MobileRelayClientService svc = service();
        svc.pushDirectory();

        assertEquals(1, bridgeBodies.size(), "4010 信封必须触发重桥接");
        assertEquals(2, dirBodies.size(), "重桥接后应重试一次");

        // 重试也被拒的话绝不能记成「已推送」：下一轮必须再出站
        server.removeContext("/api/mobile/projects");
        server.createContext("/api/mobile/projects", ex -> {
            dirBodies.add(readBody(ex.getRequestBody()));
            respond(ex, 200, "{\"code\":4010,\"message\":\"请先登录\"}");
        });
        MobileRelayClientService svc2 = service();
        svc2.pushDirectory();
        int after = dirBodies.size();
        svc2.pushDirectory();
        assertTrue(dirBodies.size() > after, "被拒的推送不得被哈希去重当成功吞掉");
    }

    @Test
    @DisplayName("内容下载拿到 JSON 信封（非 octet-stream）：不落盘不 ACK")
    void contentEnvelopeIsNotWrittenAsMedia() {
        server.removeContext("/api/mobile/inbox");
        server.createContext("/api/mobile/inbox", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/content")) {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                respond(ex, 200, "{\"code\":4010,\"message\":\"请先登录\"}");
            } else if (path.endsWith("/ack")) {
                acked.add(path);
                respond(ex, 200, "{\"code\":0}");
            } else {
                respond(ex, 200, inboxJson);
            }
        });
        inboxJson = "[{\"id\":9,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"IMG_0001.jpg\",\"mediaType\":\"image\",\"fileSize\":10}]";
        service().pollInbox();
        assertTrue(savedBytes.isEmpty(), "JSON 信封绝不能被当成照片字节落盘");
        assertTrue(acked.isEmpty(), "没落盘就不许 ACK");
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
