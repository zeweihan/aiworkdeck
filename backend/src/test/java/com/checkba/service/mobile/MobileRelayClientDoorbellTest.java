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

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桌面端门铃流的两条「常连还活着，但其实已经没用了」的回归（v0.44.1 走查）。对着真 HTTP 桩跑：
 *
 * <ol>
 *   <li><b>取件不许占住读流的那条线程</b>：一次 PULL/PUSH 最长十分钟（{@code Duration.ofMinutes(10)}），
 *       在门铃线程上直接取件的话，这十分钟里后来的每一条 nudge 都读不到；而云端
 *       {@link ReferenceRequestStore#TTL_MS} 只有 60 秒、{@code DesktopStreamService.isOnline}
 *       仍报在线，于是参考读取被照常受理、然后白等到超时。</li>
 *   <li><b>换账号要重建这条流</b>：云端在建连那一刻把流登记在 (userId, deviceId) 名下，之后不再
 *       重新鉴权。其余出站都经 {@code currentToken()} 自动改投新账号，唯独这条流留在旧账号上，
 *       新账号那边 {@code isOnline} 恒为假，desk: 来源整块失效。</li>
 * </ol>
 *
 * <p>两条都要防空断言：第一条要先确认传输取件<b>确实还堵着</b>再看参考取件跑完了，
 * 第二条要看第二次建流用的是<b>新账号换来的新令牌</b>，光数连接次数证不了「改投了新账号」。
 *
 * <p>外加第三条（2026-09-20）：非 2xx 早退时必须关掉惰性响应体，别把 HTTP 流漏在
 * 全类共用的那个 HttpClient 上——见 {@code doorbellNon2xxClosesTheLazyBodyStream}。
 */
class MobileRelayClientDoorbellTest {

    private HttpServer server;
    private String baseUrl;

    private AccountService accountService;
    private MobileRelayClientService service;

    @TempDir Path stateDir;

    /** 门铃流：连接次数、每次带的令牌；第一条连接在 ready 之后额外写这些事件。 */
    private final AtomicInteger streamRequests = new AtomicInteger();
    private final List<String> streamTokens = new CopyOnWriteArrayList<>();
    private volatile List<String> firstConnectionEvents = List.of();

    /** 传输取件：调用次数、当前在处理中的条数、放行闩。 */
    private final AtomicInteger transferCalls = new AtomicInteger();
    private final AtomicInteger transferInFlight = new AtomicInteger();
    private final CountDownLatch transferHold = new CountDownLatch(1);

    private final List<String> refResultPaths = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String refRequestsJson =
            "{\"code\":0,\"requests\":[{\"id\":\"r1\",\"kind\":\"READ\",\"projectKey\":\"42\",\"path\":\"说明.txt\"}]}";

    private volatile boolean stopping = false;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // SSE 桩与被堵住的传输桩各占一条处理线程：默认的单线程派发会把其余端点一起堵死
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/auth/awdk-login", ex ->
                respond(ex, 200, "{\"code\":0,\"data\":{\"token\":\"awdt_" + logins.incrementAndGet()
                        + "\",\"userId\":3}}"));

        server.createContext("/api/mobile/desktop/stream", ex -> {
            int n = streamRequests.incrementAndGet();
            streamTokens.add(ex.getRequestHeaders().getFirst("X-Session-Id"));
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            try {
                write(os, "event:ready\ndata:{}\n\n");
                if (n == 1) {
                    for (String event : firstConnectionEvents) write(os, event);
                }
                // 云端真实行为：每 15 秒一个 ping。这里压到 100ms，让用例不用等一刻钟
                while (!stopping) {
                    write(os, "event:ping\ndata:{\"ts\":1}\n\n");
                    Thread.sleep(100);
                }
            } catch (Exception closed) {
                // 对端断开（换账号重建时就是这样）或测试收尾
            }
            ex.close();
        });

        server.createContext("/api/mobile/transfer", ex -> {
            transferCalls.incrementAndGet();
            transferInFlight.incrementAndGet();
            try {
                // 真实形态是一次 200MB 的上传/下载（request 超时给到 10 分钟），这里用闩代替
                transferHold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                transferInFlight.decrementAndGet();
            }
            respond(ex, 200, "{\"code\":0,\"commands\":[]}");
        });

        server.createContext("/api/mobile/ref", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/result")) {
                readBody(ex.getRequestBody());
                refResultPaths.add(path);
                respond(ex, 200, "{\"code\":0}");
                return;
            }
            String json = refRequestsJson;
            refRequestsJson = "{\"code\":0,\"requests\":[]}"; // 同一条不下发第二次（服务端语义）
            respond(ex, 200, json);
        });

        server.createContext("/api/mobile/inbox", ex -> respond(ex, 200, "[]"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        accountService = mock(AccountService.class);
        when(accountService.currentKeyOrNull()).thenReturn("awdk_key_1");
        when(accountService.accountFingerprintOrNull()).thenReturn("fp-1");

        LocalIdentityService localIdentityService = mock(LocalIdentityService.class);
        when(localIdentityService.localUserId()).thenReturn(7L);

        ProjectRepository projectRepository = mock(ProjectRepository.class);
        Project p42 = new Project();
        p42.setId(42L);
        p42.setName("金冠纾困");
        p42.setUserId(7L);
        when(projectRepository.findById(42L)).thenReturn(Optional.of(p42));

        DesktopRefHandler refHandler = mock(DesktopRefHandler.class);
        when(refHandler.handle(any())).thenReturn(Map.of("ok", true, "text", "说明正文"));

        service = new MobileRelayClientService(true, true, baseUrl,
                "https://www.aiworkdeck.com", stateDir.toString(),
                accountService, localIdentityService, projectRepository,
                mock(ProjectFileService.class), mock(StorageServiceFactory.class),
                mock(com.checkba.service.ProjectAiMessageService.class), refHandler);
    }

    @AfterEach
    void tearDown() {
        stopping = true;
        transferHold.countDown();
        service.stopDoorbell();
        server.stop(0);
    }

    @Test
    @DisplayName("一条十分钟的传输取件不许挡住后面的参考取件（门铃线程只读流）")
    void slowTransferNudgeDoesNotBlockReferenceNudge() throws Exception {
        firstConnectionEvents = List.of(
                "event:nudge\ndata:{\"kind\":\"transfer\"}\n\n",
                "event:nudge\ndata:{\"kind\":\"ref\"}\n\n");

        service.startDoorbell();

        // 防空断言：先确认传输取件真的开始了，否则下面那条断言证不了「没被它挡住」
        awaitTrue(() -> transferCalls.get() >= 1, "传输取件应当已经发出");
        awaitTrue(() -> !refResultPaths.isEmpty(), "传输取件还堵着的时候，参考取件也必须跑得动");
        assertTrue(transferInFlight.get() >= 1,
                "参考取件回传时传输取件应当仍在处理中，否则这条用例没有覆盖到并发");
        assertEquals(List.of("/api/mobile/ref/r1/result"), refResultPaths);
    }

    @Test
    @DisplayName("换账号：门铃流用新账号的令牌重建（旧流还登记在旧账号名下，留着等于 desk: 全瞎）")
    void doorbellRebindsAfterAccountSwitch() throws Exception {
        service.startDoorbell();

        awaitTrue(() -> streamRequests.get() >= 1, "应当先建起第一条门铃流");
        assertEquals("awdt_1", streamTokens.get(0));

        when(accountService.currentKeyOrNull()).thenReturn("awdk_key_2");
        when(accountService.accountFingerprintOrNull()).thenReturn("fp-2");

        awaitTrue(() -> streamRequests.get() >= 2, "换账号后应当重建门铃流");
        assertEquals("awdt_2", streamTokens.get(1),
                "重建的流必须用新账号桥接出来的令牌，否则云端仍把它记在旧账号名下");
    }

    // ==================== 门铃流（dev-board#719） ====================

    /**
     * 门铃流非 2xx 收工前必须关掉响应体。
     *
     * <p>{@code BodyHandlers.ofLines()} 给的是惰性流：不消费也不 close，这条 HTTP 流就一直挂着。
     * 而门铃在 relay 502/503 期间会按退避一遍遍重连（最快 60 秒一次），每次漏一条，
     * 攒够 HTTP/2 的并发流上限就把共用同一个 HttpClient 的取件轮询、传输命令、
     * 参考结果回传一起拖死，直到重启进程。
     */
    @Test
    @DisplayName("门铃流非 2xx：三条早退路径都关掉惰性响应体，不把 HTTP 流漏在共用客户端上")
    void doorbellNon2xxClosesTheLazyBodyStream() {
        for (int status : new int[]{404, 401, 503}) {
            java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.stream.Stream<String> body = java.util.stream.Stream.<String>of("event: error")
                    .onClose(() -> closed.set(true));

            assertNotNull(service.doorbellEarlyExit(status, body), "非 2xx 必须就此收工：" + status);
            assertTrue(closed.get(), "状态 " + status + " 的响应体没被关掉，这条 HTTP 流就漏了");
        }
    }

    /**
     * 退避只在「这条流活够稳定期」之后复位。
     *
     * <p>云端 {@code DesktopStreamService.connect} 建连那一刻就无条件写 {@code event:ready}，
     * 所以 CONNECTED 只等于「请求拿到了响应」。relay 前面的 nginx 对 SSE 配错/过载、
     * 发完响应头几百毫秒就关流时，每一轮都是 CONNECTED——按 ready 复位的话，
     * 装机的每台桌面端都变成 1 Hz 重连（每次重连云端还要按 awdt_ 令牌查一次库），
     * 且永远不会自己好。插件侧 SSE 早就踩过同一个形状（sse.js STABLE_CONNECTION_MS，
     * dev-board#285），这里不能再来一遍。
     */
    @Test
    @DisplayName("门铃退避：连上即被断的流继续翻倍到上限，只有活够稳定期的连接才复位")
    void doorbellBackoffOnlyResetsAfterAStableConnection() {
        long min = MobileRelayClientService.DOORBELL_MIN_BACKOFF_MS;
        long max = MobileRelayClientService.DOORBELL_MAX_BACKOFF_MS;
        long stable = MobileRelayClientService.DOORBELL_STABLE_MS;

        assertEquals(min * 2,
                MobileRelayClientService.nextDoorbellBackoff(min, MobileRelayClientService.Doorbell.CONNECTED, 50),
                "连上 50 毫秒就断，第一轮就该开始翻倍");

        long backoff = min;
        for (int i = 0; i < 10; i++) {
            backoff = MobileRelayClientService.nextDoorbellBackoff(
                    backoff, MobileRelayClientService.Doorbell.CONNECTED, stable - 1);
        }
        assertEquals(max, backoff, "一直连上即断的话退避要顶到上限，而不是被按在最小值上");

        assertEquals(min, MobileRelayClientService.nextDoorbellBackoff(
                max, MobileRelayClientService.Doorbell.CONNECTED, stable), "活够稳定期才复位");
        assertEquals(min, MobileRelayClientService.nextDoorbellBackoff(
                max, MobileRelayClientService.Doorbell.REBIND, 5), "换账号是本机主动断的，照常立刻重连");
        assertEquals(max, MobileRelayClientService.nextDoorbellBackoff(
                min, MobileRelayClientService.Doorbell.SUPERSEDED, stable * 2), "被顶掉一律按最大退避等");
        assertEquals(max, MobileRelayClientService.nextDoorbellBackoff(
                max, MobileRelayClientService.Doorbell.FAILED, 0), "翻倍不越过上限");
    }

    @Test
    @DisplayName("门铃流 2xx：不碰响应体，交给调用方一行行读下去")
    void doorbell2xxLeavesTheBodyToTheCaller() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.stream.Stream<String> body = java.util.stream.Stream.<String>of("event: ready")
                .onClose(() -> closed.set(true));

        assertNull(service.doorbellEarlyExit(200, body));
        assertFalse(closed.get(), "2xx 的流要留给调用方读，不能在这里关掉");
    }

    // ==================== 桩工具 ====================

    private static void write(OutputStream os, String event) throws IOException {
        os.write(event.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
