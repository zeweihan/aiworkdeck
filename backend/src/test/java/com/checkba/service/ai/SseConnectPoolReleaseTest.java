// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

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
 * SSE 长连接不许占住数据库连接（v0.38.3 真渲染走查 D1：连接池被占满、后端整体卡死）。
 *
 * <p>病灶：spring.jpa.open-in-view 默认开启，OSIV 拦截器给每个请求绑一个 EntityManager；
 * connect 端点做归属校验（canUseConversation 查库）时它拿到一条 JDBC 连接，而 Spring 下
 * Hibernate 的连接模式是 DELAYED_ACQUISITION_AND_HOLD——要到 EntityManager 关闭才还。
 * 异步请求（SseEmitter）里这个 EntityManager 活到整条流结束，流开着就一直占着；
 * 客户端断开走的是 onError 路径，连接同样没有还回来。连接池一共 10 条，
 * 切几次会话就满，之后所有接口先等 30 秒再 500。
 *
 * <p>真实 Tomcat + 真实 Hikari + 默认 OSIV 配置跑完整链路，只看连接池活跃数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:sse-pool;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.license.dir=${java.io.tmpdir}/awd-sse-pool-license"
})
@ActiveProfiles("desktop")
class SseConnectPoolReleaseTest {

    private static final int STREAMS = 4;

    /** 同 DesktopContextSmokeTest：desktop profile 的本机身份会静态注册到 AuthController，收尾要清掉 */
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
    private SseEmitterService sseEmitterService;

    private int baseline;

    @BeforeEach
    void captureBaseline() throws Exception {
        // 启动期的定时任务可能正借着连接：等一会儿再取基线。取「当前值」而不是假定 0，
        // 两个用例共用一个容器，前一个用例在修复前会留下被占的连接。
        waitUntil(() -> active() == 0, 5_000);
        baseline = active();
    }

    @Test
    void openStreamsDoNotHoldDatabaseConnections() throws Exception {
        List<Socket> sockets = openStreams();
        try {
            boolean released = waitUntil(() -> active() <= baseline, 5_000);
            assertTrue(released, "SSE 流开着时占住了数据库连接：基线 " + baseline
                    + "，开 " + STREAMS + " 条流后活跃 " + active());
        } finally {
            closeAll(sockets);
        }
    }

    @Test
    void disconnectedStreamsGiveTheirConnectionsBack() throws Exception {
        closeAll(openStreams());
        // 服务端要等下一次写才发现断线：心跳多扫几轮
        // （第一次写可能还能进内核缓冲，后面才吃到 Broken pipe）
        for (int round = 0; round < 3; round++) {
            sseEmitterService.heartbeatSweep();
            Thread.sleep(300);
        }
        boolean released = waitUntil(() -> active() <= baseline, 10_000);
        assertTrue(released, "客户端断开、心跳探出断线后数据库连接仍未还回连接池：基线 " + baseline
                + "，现在活跃 " + active());
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
            sockets.add(openStream("conv-pool-" + System.nanoTime() + "-" + i));
        }
        return sockets;
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket s : sockets) {
            try { s.close(); } catch (Exception ignored) { /* 已关 */ }
        }
    }

    /** 建一条 SSE 流并读到 run_state 事件为止（此时控制器已返回、异步处理已开始） */
    private Socket openStream(String conversationId) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/agent/connect/" + conversationId + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder seen = new StringBuilder();
        byte[] buf = new byte[1024];
        while (!seen.toString().contains("run_state")) {
            int n = in.read(buf);
            if (n < 0) break;
            seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        String head = seen.toString();
        assertTrue(head.startsWith("HTTP/1.1 200"), "connect 应返回 200：" + head);
        assertTrue(head.contains("connected"), "应收到 connected 事件：" + head);
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
