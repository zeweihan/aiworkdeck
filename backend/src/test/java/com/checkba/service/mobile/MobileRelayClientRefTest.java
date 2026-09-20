// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountService;
import com.checkba.storage.StorageServiceFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桌面端门铃流客户端（dev-board#719，spec 第 5 节）对着**真 HTTP 桩**跑：
 * 门铃一响立刻取件、处理、回传；旧服务器（404）进程内钉死不再骚扰，60 秒轮询照旧兜底。
 *
 * <p>门铃只是「快一点」，不是新的可靠通道——所以 404 之后 {@code pollInbox} 必须一如既往地
 * 请求 {@code /api/mobile/inbox}。这条断言是这个测试的重点：钉死写错位置会把整条取件链路
 * 一起钉死，而那是静默的。
 */
class MobileRelayClientRefTest {

    private HttpServer server;
    private String baseUrl;

    private AccountService accountService;
    private LocalIdentityService localIdentityService;
    private ProjectRepository projectRepository;
    private ProjectFileService projectFileService;
    private StorageServiceFactory storageServiceFactory;
    private com.checkba.service.ProjectAiMessageService projectAiMessageService;
    private DesktopRefHandler refHandler;

    @TempDir Path stateDir;

    private final AtomicInteger streamRequests = new AtomicInteger();
    private final AtomicInteger refRequestCalls = new AtomicInteger();
    private final AtomicInteger inboxCalls = new AtomicInteger();
    private final List<String> refResultPaths = new CopyOnWriteArrayList<>();
    private final List<String> refResultBodies = new CopyOnWriteArrayList<>();
    private final CountDownLatch streamHold = new CountDownLatch(1);

    private volatile int streamStatus = 200;
    private volatile int refRequestsStatus = 200;
    private volatile String refRequestsJson =
            "{\"code\":0,\"requests\":[{\"id\":\"r1\",\"kind\":\"READ\",\"projectKey\":\"42\",\"path\":\"说明.txt\"}]}";

    private MobileRelayClientService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // SSE 桩要一直占着一条处理线程：默认执行器是单线程派发，会把其余端点一起堵死
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/auth/awdk-login", ex ->
                respond(ex, 200, "{\"code\":0,\"data\":{\"token\":\"awdt_test_token\",\"userId\":3}}"));
        server.createContext("/api/mobile/desktop/stream", ex -> {
            streamRequests.incrementAndGet();
            if (streamStatus != 200) {
                respond(ex, streamStatus, "not found");
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write("event:ready\ndata:{}\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("event:nudge\ndata:{\"kind\":\"ref\"}\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                streamHold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.createContext("/api/mobile/ref", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/result")) {
                refResultPaths.add(path);
                refResultBodies.add(readBody(ex.getRequestBody()));
                respond(ex, 200, "{\"code\":0}");
                return;
            }
            refRequestCalls.incrementAndGet();
            if (refRequestsStatus != 200) {
                respond(ex, refRequestsStatus, "not found");
                return;
            }
            String json = refRequestsJson;
            refRequestsJson = "{\"code\":0,\"requests\":[]}"; // 同一条不下发第二次（服务端语义）
            respond(ex, 200, json);
        });
        server.createContext("/api/mobile/inbox", ex -> {
            inboxCalls.incrementAndGet();
            respond(ex, 200, "[]");
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

        projectFileService = mock(ProjectFileService.class);
        storageServiceFactory = mock(StorageServiceFactory.class);
        projectAiMessageService = mock(com.checkba.service.ProjectAiMessageService.class);

        refHandler = mock(DesktopRefHandler.class);
        when(refHandler.handle(any())).thenReturn(Map.of("ok", true, "text", "说明正文"));

        service = new MobileRelayClientService(true, true, baseUrl,
                "https://www.aiworkdeck.com", stateDir.toString(),
                accountService, localIdentityService, projectRepository,
                projectFileService, storageServiceFactory, projectAiMessageService, refHandler);
    }

    @AfterEach
    void tearDown() {
        service.stopDoorbell();
        streamHold.countDown();
        server.stop(0);
    }

    private static String readBody(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    @Test
    @DisplayName("门铃 nudge{kind:ref}：立刻取件、交给处理器、把结果回传到 /ref/{id}/result")
    void nudgeTriggersImmediateFetchAndResultPost() throws Exception {
        service.startDoorbell();

        awaitTrue(() -> !refResultBodies.isEmpty(), "门铃响过之后应当有一次结果回传");
        assertEquals(List.of("/api/mobile/ref/r1/result"), refResultPaths);
        String body = refResultBodies.get(0);
        assertTrue(body.contains("\"ok\":true"), body);
        assertTrue(body.contains("说明正文"), body);
        assertTrue(refRequestCalls.get() >= 1);
    }

    @Test
    @DisplayName("门铃流 404（旧服务器）：进程内钉死不再连，但 60 秒取件轮询照旧")
    void stream404PinsAndKeepsPolling() throws Exception {
        streamStatus = 404;
        service.startDoorbell();
        awaitTrue(() -> streamRequests.get() >= 1, "至少应当尝试连一次门铃流");

        service.ensureDoorbell();
        service.ensureDoorbell();
        service.pollInbox();
        Thread.sleep(200);

        assertEquals(1, streamRequests.get(), "404 之后不该再连门铃流");
        assertTrue(inboxCalls.get() >= 1, "门铃不可用不影响既有的 60 秒取件轮询");
    }

    @Test
    @DisplayName("/ref/requests 404（旧服务器）：同款进程内钉死，后续不再请求")
    void refRequests404PinsSilently() {
        refRequestsStatus = 404;

        service.pollReferenceRequests();
        service.pollReferenceRequests();
        service.pollReferenceRequests();

        assertEquals(1, refRequestCalls.get(), "404 之后不该再打这个端点");
        assertTrue(refResultBodies.isEmpty());
    }

    @Test
    @DisplayName("取件轮询挂在 pollInbox 的 finally 上：收件箱为空（提前 return）也照样跑")
    void pollInboxAlsoDrainsReferenceRequests() throws Exception {
        service.pollInbox();

        awaitTrue(() -> !refResultBodies.isEmpty(), "收件箱空时 pollInbox 仍应捎带取参考请求");
        assertEquals(List.of("/api/mobile/ref/r1/result"), refResultPaths);
    }
}
