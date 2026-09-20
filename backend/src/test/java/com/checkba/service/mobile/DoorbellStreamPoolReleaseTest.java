// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ai.tools.WebTools;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端门铃流（dev-board#719）不许占住数据库连接——与 {@code SseConnectPoolReleaseTest}
 * 守的是同一个病灶（v0.38.3 走查 D1），只是换了个长连接端点。
 *
 * <p>{@code GET /api/mobile/desktop/stream} 是 {@code SseEmitter(0L)}：桌面端开着就一直连着。
 * handler 第一件事是拿 {@code awdt_} 设备令牌查库（DeviceTokenService.resolve），这条查询在
 * OSIV 绑定的 EntityManager 上取到 JDBC 连接，而 Hibernate 在 Spring 下是
 * DELAYED_ACQUISITION_AND_HOLD——要到 EntityManager 关闭（异步请求结束 = 整条流结束）才还。
 * Hikari 默认 10 条，十来台桌面端同时在线就把池占满，之后全站接口先等 30 秒再 500。
 * 修法是把该路径排除出 OSIV（{@code OpenEntityManagerInViewConfig.LONG_LIVED_STREAM_PATHS}）。
 *
 * <p>真实 Tomcat + 真实 Hikari + 真实 awdt_ 鉴权跑完整链路，只看连接池活跃数。
 * 鉴权必须走设备令牌那一支：换成内存里的登录会话就不查库，这条用例会变成空断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:doorbell-pool;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "security.license.dir=${java.io.tmpdir}/awd-doorbell-pool-license",
        "storage.local.root-path=${java.io.tmpdir}/awd-doorbell-pool-store"
})
@ActiveProfiles("desktop")
class DoorbellStreamPoolReleaseTest {

    private static final int STREAMS = 4;
    private static final Long USER_ID = 90210L;

    /** 同 SseConnectPoolReleaseTest：desktop profile 的本机身份会静态注册到 AuthController，收尾清掉 */
    @AfterAll
    static void resetLocalIdentityStatic() {
        com.checkba.controller.AuthController.registerLocalIdentityService(null);
    }

    /** 真 WebTools 的 @PostConstruct 会起线程预热 Playwright，测试里挡掉 */
    @MockBean
    private WebTools webTools;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DeviceTokenService deviceTokenService;

    private String token;
    private int baseline;

    @BeforeEach
    void issueTokenAndCaptureBaseline() throws Exception {
        token = deviceTokenService.issue(USER_ID, "门铃连接池用例").plaintext();
        // 启动期的定时任务可能正借着连接：等一会儿再取基线
        waitUntil(() -> active() == 0, 5_000);
        baseline = active();
    }

    @Test
    void openDoorbellStreamsDoNotHoldDatabaseConnections() throws Exception {
        List<Socket> sockets = openStreams();
        try {
            boolean released = waitUntil(() -> active() <= baseline, 5_000);
            assertTrue(released, "门铃流开着时占住了数据库连接：基线 " + baseline
                    + "，开 " + STREAMS + " 条流后活跃 " + active());
        } finally {
            closeAll(sockets);
        }
    }

    private int active() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Socket> openStreams() throws Exception {
        List<Socket> sockets = new ArrayList<>();
        for (int i = 0; i < STREAMS; i++) {
            sockets.add(openStream("doorbell-pool-" + System.nanoTime() + "-" + i));
        }
        return sockets;
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket s : sockets) {
            try { s.close(); } catch (Exception ignored) { /* 已关 */ }
        }
    }

    /** 建一条门铃流并读到 ready 事件为止（此时控制器已返回、鉴权那一次查库已经发生过） */
    private Socket openStream(String deviceId) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/mobile/desktop/stream?deviceId=" + deviceId + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "X-Session-Id: " + token + "\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder seen = new StringBuilder();
        byte[] buf = new byte[1024];
        while (!seen.toString().contains("ready")) {
            int n = in.read(buf);
            if (n < 0) break;
            seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        String head = seen.toString();
        // 401 的话一条库也不会查，本用例会假绿：这两句是防空断言的正控制
        assertTrue(head.startsWith("HTTP/1.1 200"), "门铃流应返回 200：" + head);
        assertTrue(head.contains("event:ready"), "应收到 ready 事件：" + head);
        return socket;
    }

    private static boolean waitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }
}
