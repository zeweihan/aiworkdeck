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
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
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
    private com.checkba.service.ProjectAiMessageService projectAiMessageService;

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
    // save/createFile 的相对调用顺序：验证 PUSH「字节先落盘后 createFile」的顺序红线
    private final List<String> callOrder = new CopyOnWriteArrayList<>();

    // ==================== 传输命令（dev-board#251 B 侧）测试夹具 ====================
    private volatile String transferCommandsJson = "{\"code\":0,\"commands\":[],\"hot\":false}";
    private volatile int transferCommandsStatus = 200;
    private final AtomicInteger transferCommandsRequests = new AtomicInteger();
    private final List<String> transferFilesBodies = new CopyOnWriteArrayList<>();
    private final List<byte[]> transferUploadBodies = new CopyOnWriteArrayList<>();
    private final List<String> transferFailBodies = new CopyOnWriteArrayList<>();
    private final List<String> transferAcked = new CopyOnWriteArrayList<>();
    private volatile String pushContentType = "application/octet-stream";
    private volatile String pushContentBody = "PUSH-BYTES";

    // ==================== 插件对话镜像（dev-board#298）测试夹具 ====================
    private volatile String convSyncJson = "[]";
    private volatile int convSyncStatus = 200;
    private final AtomicInteger convSyncRequests = new AtomicInteger();
    private final List<String> convAckBodies = new CopyOnWriteArrayList<>();

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
        server.createContext("/api/mobile/transfer", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/mobile/transfer/commands")) {
                transferCommandsRequests.incrementAndGet();
                if (transferCommandsStatus != 200) {
                    respond(ex, transferCommandsStatus, "not found");
                } else {
                    respond(ex, 200, transferCommandsJson);
                }
            } else if (path.endsWith("/files")) {
                transferFilesBodies.add(readBody(ex.getRequestBody()));
                respond(ex, 200, "{\"code\":0}");
            } else if (path.endsWith("/upload")) {
                transferUploadBodies.add(ex.getRequestBody().readAllBytes());
                respond(ex, 200, "{\"code\":0}");
            } else if (path.endsWith("/content")) {
                ex.getResponseHeaders().add("Content-Type", pushContentType);
                respond(ex, 200, pushContentBody);
            } else if (path.endsWith("/ack")) {
                transferAcked.add(path);
                respond(ex, 200, "{\"code\":0}");
            } else if (path.endsWith("/fail")) {
                transferFailBodies.add(readBody(ex.getRequestBody()));
                respond(ex, 200, "{\"code\":0}");
            } else {
                respond(ex, 404, "not found");
            }
        });
        server.createContext("/api/mobile/conversations", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/ack")) {
                convAckBodies.add(readBody(ex.getRequestBody()));
                respond(ex, 200, "{\"code\":0,\"deleted\":1}");
            } else {
                convSyncRequests.incrementAndGet();
                if (convSyncStatus != 200) {
                    respond(ex, convSyncStatus, "not found");
                } else {
                    respond(ex, 200, convSyncJson);
                }
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
        when(projectFileService.getFileTree(anyLong())).thenAnswer(inv -> new ArrayList<>(tree));
        when(projectFileService.getFile(anyLong())).thenAnswer(inv -> {
            Long fileId = inv.getArgument(0);
            return tree.stream().filter(f -> fileId.equals(f.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
        });
        when(projectFileService.createFolder(anyLong(), any(), anyString(), anyLong()))
                .thenAnswer(inv -> addNode(inv.getArgument(1), inv.getArgument(2), true));
        when(projectFileService.createFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    callOrder.add("createFile:" + inv.getArgument(2));
                    ProjectFile f = addNode(inv.getArgument(1), inv.getArgument(2), false);
                    f.setFilePath("projects/42/" + f.getName());
                    return f;
                });

        storageService = mock(StorageService.class);
        when(storageService.save(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            callOrder.add("save:" + inv.getArgument(0));
            savedStorageKeys.add(inv.getArgument(0));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            inv.getArgument(1, InputStream.class).transferTo(buf);
            savedBytes.add(buf.toByteArray());
            return inv.getArgument(0);
        });
        doAnswer(inv -> {
            callOrder.add("move:" + inv.getArgument(0) + "->" + inv.getArgument(1));
            return null;
        }).when(storageService).move(anyString(), anyString());
        when(projectFileService.createOrUpdateFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    callOrder.add("createOrUpdateFile:" + inv.getArgument(2));
                    ProjectFile f = addNode(inv.getArgument(1), inv.getArgument(2), false);
                    f.setFilePath(inv.getArgument(5));
                    return f;
                });
        storageServiceFactory = mock(StorageServiceFactory.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        projectAiMessageService = mock(com.checkba.service.ProjectAiMessageService.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        // 热窗口测试会调低这两个包可见常量，不重置会污染后面的测试（真等 120 秒才退出循环）
        MobileRelayClientService.TRANSFER_HOT_WINDOW_MS = 120_000L;
        MobileRelayClientService.TRANSFER_HOT_POLL_INTERVAL_MS = 5_000L;
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
                projectFileService, storageServiceFactory, projectAiMessageService);
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

    /**
     * 空清单防顶掉（线上实测）：本机常态跑着 e2e/dev/优化者多个后端实例，凡是不改
     * user.home 的实例都共享同一份 ~/.aiworkdeck/mobile-relay.json 的 relay 身份——
     * 测试实例本地库是空的，一次空清单推送就把真桌面端在云端的目录整批顶成 0 行。
     * 本地项目列表为空时干脆不出站（服务端另有同语义守卫，双保险）。
     */
    @Test
    @DisplayName("本地项目列表为空：跳过目录推送，一条出站都不发")
    void pushDirectorySkipsWhenLocalProjectListEmpty() {
        when(projectRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
        service().pushDirectory();

        assertTrue(dirBodies.isEmpty(), "空清单不该出站——那会顶掉真桌面端的云端目录");
        assertTrue(bridgeBodies.isEmpty(), "没有出站需求就不该触发桥接");
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
    @DisplayName("音频取件（mediaType=audio）：落「现场录音/日期」而不是「现场影像」，storagePath 同构")
    void pollInboxLandsAudioIntoAudioFolder() {
        inboxJson = "[{\"id\":11,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"现场谈话.m4a\",\"mediaType\":\"audio\",\"fileSize\":10,"
                + "\"capturedAt\":\"2026-08-19T21:00:00\",\"createdAt\":\"2026-08-20T09:00:00\"}]";
        service().pollInbox();

        assertTrue(tree.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && "现场录音".equals(f.getName())));
        assertFalse(tree.stream().anyMatch(f -> "现场影像".equals(f.getName())), "音频不该建影像根目录");
        assertTrue(tree.stream().anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder())
                && "现场谈话-0a1b2c3d.m4a".equals(f.getName())));
        assertEquals(1, savedStorageKeys.size());
        assertTrue(savedStorageKeys.get(0).startsWith("projects/42/现场录音/2026-08-19/"),
                "storagePath 必须与 ensureFolder 的目录同构，实际: " + savedStorageKeys.get(0));
        assertEquals(List.of("/api/mobile/inbox/11/ack"), acked);
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
                projectFileService, storageServiceFactory, projectAiMessageService);
        notLocal.pushDirectory();
        assertTrue(dirBodies.isEmpty(), "非 local-mode（云端/团队服务器）绝不出站");
    }

    // ==================== 跨设备文件传输（dev-board#251 B 侧） ====================

    @Test
    @DisplayName("传输命令 LIST：清单按 parentId 逐级拼路径上报，文件夹本身不进清单")
    void transferListCommandReportsFileTreeWithPaths() {
        ProjectFile folder = addNode(null, "合同", true);
        ProjectFile f1 = addNode(folder.getId(), "结算书.docx", false);
        f1.setFileSize(2048L);
        ProjectFile f2 = addNode(null, "备忘录.txt", false);
        f2.setFileSize(100L);

        transferCommandsJson = "{\"code\":0,\"commands\":[{\"id\":1,\"kind\":\"LIST\",\"projectKey\":\"42\"}],\"hot\":false}";
        service().pollInbox();

        assertEquals(1, transferFilesBodies.size());
        String body = transferFilesBodies.get(0);
        assertTrue(body.contains("\"path\":\"合同/结算书.docx\""), body);
        assertTrue(body.contains("\"size\":2048"), body);
        assertTrue(body.contains("\"path\":\"备忘录.txt\""), body);
        assertTrue(body.contains("\"size\":100"), body);
        assertFalse(body.contains("\"name\":\"合同\""), "文件夹本身不该进清单: " + body);
    }

    @Test
    @DisplayName("传输命令 PULL：本机文件流式回传，multipart 请求体含文件字节与 filename")
    void transferPullCommandUploadsFileBytes() {
        ProjectFile file = addNode(null, "合同.docx", false);
        file.setFilePath("projects/42/合同.docx");
        when(storageService.load("projects/42/合同.docx"))
                .thenReturn(new ByteArrayResource("CONTRACT-BYTES".getBytes(StandardCharsets.UTF_8)));

        transferCommandsJson = "{\"code\":0,\"commands\":[{\"id\":2,\"kind\":\"PULL\",\"projectKey\":\"42\","
                + "\"remoteFileId\":\"" + file.getId() + "\",\"fileName\":\"合同.docx\",\"fileSize\":14}],\"hot\":false}";
        service().pollInbox();

        assertEquals(1, transferUploadBodies.size());
        String raw = new String(transferUploadBodies.get(0), StandardCharsets.UTF_8);
        assertTrue(raw.contains("filename=\"合同.docx\""), raw);
        assertTrue(raw.contains("CONTRACT-BYTES"), raw);
    }

    @Test
    @DisplayName("传输命令 PUSH：落两级目录、字节先落盘后 createFile、成功后 ACK")
    void transferPushCommandLandsFileThenAcks() {
        pushContentType = "application/octet-stream";
        pushContentBody = "PUSH-BYTES";
        transferCommandsJson = "{\"code\":0,\"commands\":[{\"id\":3,\"kind\":\"PUSH\",\"projectKey\":\"42\","
                + "\"fileName\":\"report.docx\",\"fileSize\":10}],\"hot\":false}";
        service().pollInbox();

        String today = LocalDate.now().toString();
        assertTrue(tree.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && "跨设备文件".equals(f.getName())));
        assertTrue(tree.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && today.equals(f.getName())));
        assertTrue(tree.stream().anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder())
                && "report-t3.docx".equals(f.getName())));
        assertEquals(1, savedBytes.size());
        assertEquals("PUSH-BYTES", new String(savedBytes.get(0), StandardCharsets.UTF_8));
        assertTrue(savedStorageKeys.get(0).startsWith("projects/42/跨设备文件/" + today + "/"),
                savedStorageKeys.get(0));
        assertEquals(List.of("/api/mobile/transfer/3/ack"), transferAcked);

        int saveIdx = callOrder.indexOf("save:" + savedStorageKeys.get(0));
        int createIdx = callOrder.indexOf("createFile:report-t3.docx");
        assertTrue(saveIdx >= 0 && createIdx >= 0 && saveIdx < createIdx,
                "字节必须先落盘再 createFile，实际顺序: " + callOrder);
    }

    @Test
    @DisplayName("传输命令：项目不存在或不属本机用户，POST /fail 触发云端退款（不是留置）")
    void transferCommandProjectNotFoundReportsFail() {
        transferCommandsJson = "{\"code\":0,\"commands\":[{\"id\":4,\"kind\":\"PUSH\",\"projectKey\":\"999\","
                + "\"fileName\":\"x.docx\",\"fileSize\":1}],\"hot\":false}";
        service().pollInbox();

        assertEquals(1, transferFailBodies.size());
        assertTrue(transferFailBodies.get(0).contains("项目不存在或已删除"), transferFailBodies.get(0));
        assertTrue(transferAcked.isEmpty());
    }

    @Test
    @DisplayName("传输命令 PUSH：/content 返回非 octet-stream（JSON 信封）不落盘不 ACK 不 FAIL")
    void transferPushCommandRejectsNonOctetStreamContent() {
        pushContentType = "application/json";
        pushContentBody = "{\"code\":4010,\"message\":\"请先登录\"}";
        transferCommandsJson = "{\"code\":0,\"commands\":[{\"id\":5,\"kind\":\"PUSH\",\"projectKey\":\"42\","
                + "\"fileName\":\"x.docx\",\"fileSize\":1}],\"hot\":false}";
        service().pollInbox();

        assertTrue(savedBytes.isEmpty(), "JSON 信封绝不能被当成文件字节落盘");
        assertTrue(transferAcked.isEmpty());
        assertTrue(transferFailBodies.isEmpty(), "内容下载失败是瞬态问题，不该报确定性失败");
    }

    @Test
    @DisplayName("/transfer/commands 404（旧服务器）：静默跳过，进程内记住不再打这个端点")
    void transferCommands404IsSilentlySkippedAfterFirstAttempt() {
        transferCommandsStatus = 404;
        MobileRelayClientService svc = service();
        svc.pollInbox();
        svc.pollInbox();
        svc.pollInbox();

        assertEquals(1, transferCommandsRequests.get(), "404 后不该再打这个端点");
    }

    @Test
    @DisplayName("hot=true：进入热窗口短轮询，至少再拉一次 /commands（不真等 120 秒）")
    void hotFlagTriggersShortPolling() throws Exception {
        MobileRelayClientService.TRANSFER_HOT_WINDOW_MS = 300L;
        MobileRelayClientService.TRANSFER_HOT_POLL_INTERVAL_MS = 30L;
        transferCommandsJson = "{\"code\":0,\"commands\":[],\"hot\":true}";

        service().pollInbox();
        assertEquals(1, transferCommandsRequests.get());

        // 热循环在独立后台线程按 30ms 间隔追加轮询；轮询到 hot:true 会不断顺延窗口，
        // 这里只等到看见第二次请求就够，不需要等窗口真正过期。
        long deadline = System.currentTimeMillis() + 2000;
        while (transferCommandsRequests.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(transferCommandsRequests.get() >= 2, "热窗口内应至少再拉一次 /commands");
    }

    // ==================== 插件文档镜像（dev-board#299） ====================

    @Test
    @DisplayName("document 类型：落「插件文档/<原名>」固定路径，temp save → 原子 move → 建/更库 → ACK 的顺序")
    void documentMirrorLandsWithAtomicOverwrite() {
        inboxJson = "[{\"id\":21,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"股权转让协议.docx\",\"mediaType\":\"document\",\"fileSize\":9}]";
        service().pollInbox();

        assertEquals(1, savedStorageKeys.size());
        String tmpKey = savedStorageKeys.get(0);
        assertTrue(tmpKey.startsWith("projects/42/插件文档/.tmp-"), "字节必须先写临时 key：" + tmpKey);
        String moveStep = callOrder.stream().filter(s -> s.startsWith("move:")).findFirst().orElse("");
        assertEquals("move:" + tmpKey + "->projects/42/插件文档/股权转让协议.docx", moveStep,
                "move 必须从临时 key 顶替到无 marker 的最终路径（路径唯一是覆盖语义的锚点）");
        int saveIdx = callOrder.indexOf("save:" + tmpKey);
        int moveIdx = callOrder.indexOf(moveStep);
        int dbIdx = callOrder.indexOf("createOrUpdateFile:股权转让协议.docx");
        assertTrue(saveIdx >= 0 && moveIdx > saveIdx && dbIdx > moveIdx,
                "顺序必须是 save→move→createOrUpdateFile（字节先落、库后动），实际：" + callOrder);
        assertEquals(1, acked.size(), "落盘完成才 ACK");
    }

    @Test
    @DisplayName("document 落盘失败：不 ACK、不动库，旧文件因走临时 key 而完好")
    void documentMirrorSaveFailureLeavesOldFileAndNoAck() throws Exception {
        when(storageService.save(anyString(), any(InputStream.class)))
                .thenThrow(new com.checkba.storage.StorageException("disk full"));
        inboxJson = "[{\"id\":22,\"projectKey\":\"42\",\"clientMediaId\":\"" + MEDIA_ID + "\","
                + "\"fileName\":\"a.docx\",\"mediaType\":\"document\",\"fileSize\":9}]";
        service().pollInbox();

        assertTrue(acked.isEmpty(), "没落成不许 ACK（留在中转区下轮重试）");
        assertTrue(callOrder.stream().noneMatch(s -> s.startsWith("createOrUpdateFile:")),
                "字节没落好绝不动库");
    }

    // ==================== 插件对话镜像（dev-board#298 桌面侧） ====================

    @Test
    @DisplayName("对话镜像：可导入的行导入并 ACK，项目缺失的行留置不 ACK，标题以云端下发为准")
    void conversationSyncImportsAcksAndLeavesOrphans() {
        convSyncJson = "[{\"id\":1,\"projectKey\":\"42\",\"conversationId\":\"conv-1-abc\","
                + "\"sourceMessageId\":11,\"role\":\"USER\",\"content\":\"问\",\"sourceChannel\":\"wps-word\","
                + "\"title\":\"合同审查\",\"messageCreatedAt\":\"2026-08-30T10:00:00\"},"
                + "{\"id\":2,\"projectKey\":\"999\",\"conversationId\":\"conv-2-def\","
                + "\"sourceMessageId\":12,\"role\":\"USER\",\"content\":\"孤儿\",\"sourceChannel\":\"office-word\","
                + "\"messageCreatedAt\":\"2026-08-30T10:00:01\"}]";
        com.checkba.model.entity.ProjectAiMessage saved = new com.checkba.model.entity.ProjectAiMessage();
        saved.setConversationId("conv-1-abc");
        when(projectAiMessageService.importExternalMessage(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), any(), any(), any())).thenReturn(saved);

        service().pollInbox();

        verify(projectAiMessageService).importExternalMessage(eq(42L), eq(7L), eq("conv-1-abc"),
                eq("USER"), eq("问"), any(), eq("wps-word"), eq(11L),
                eq(java.time.LocalDateTime.of(2026, 8, 30, 10, 0, 0)));
        verify(projectAiMessageService, never()).importExternalMessage(eq(999L), anyLong(), anyString(),
                anyString(), anyString(), any(), any(), any(), any());
        verify(projectAiMessageService).updateConversationTitle("conv-1-abc", "合同审查");
        assertEquals(1, convAckBodies.size());
        assertTrue(convAckBodies.get(0).contains("[1]"),
                "只 ACK 导入过的行（id=1），项目缺失的 id=2 留置：" + convAckBodies.get(0));
    }

    @Test
    @DisplayName("/conversations/inbox 404（旧服务器）：静默跳过，进程内记住不再打这个端点")
    void conversationSync404IsSilentlySkippedAfterFirstAttempt() {
        convSyncStatus = 404;
        MobileRelayClientService svc = service();
        svc.pollInbox();
        svc.pollInbox();
        assertEquals(1, convSyncRequests.get(), "404 后不该再打这个端点");
    }
}
