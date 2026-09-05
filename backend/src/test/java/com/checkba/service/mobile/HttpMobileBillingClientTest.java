package com.checkba.service.mobile;

import com.checkba.service.mobile.MobileBillingClient.MobileBillingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpMobileBillingClient} 的纯单测（不起 Spring）：未配置即短路、连不上即 UNAVAILABLE、
 * resolve 的"二选一恰好一个"前置条件，以及<b>失败分类</b>。护的是 dev-board#425 最容易退化的几条——
 * 未配置时偷偷发请求、上游故障被吞成"没有账户"、409 的 outTradeNo 被丢掉。
 *
 * <p>失败分类用 JDK 自带的 {@link HttpServer} 起一个本机桩服务，回真实的状态码与响应体：
 * 这几条的判据就在 HTTP 层（空体 vs 带 body 的 404），用 mock 绕过去等于没测。
 */
class HttpMobileBillingClientTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 端口 1 恒连不上，用来制造"连得上地址、连不上服务"的网络失败。 */
    private static final String DEAD_BASE = "http://127.0.0.1:1";

    private HttpServer server;
    private String base;
    /** 桩服务当次要回的 {状态码, 响应体（null = 空体）}。 */
    private final AtomicInteger stubStatus = new AtomicInteger(200);
    private final AtomicReference<String> stubBody = new AtomicReference<>("{}");
    /** 最后一次收到的请求体，用来断言 create 位真的上行了。 */
    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/account", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = stubBody.get();
            byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (out.length == 0) {
                // -1 = 明确的"没有响应体"，即官网对 env 未配/secret 不符回的那种空体 404
                exchange.sendResponseHeaders(stubStatus.get(), -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(stubStatus.get(), out.length);
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpMobileBillingClient stubbed(int status, String body) {
        stubStatus.set(status);
        stubBody.set(body);
        return new HttpMobileBillingClient(base, "secret", om);
    }

    // ==================== 短路与网络 ====================

    @Test
    @DisplayName("base-url/secret 任一未配置：四个动作一律 DISABLED，且不发请求")
    void unconfiguredShortCircuitsToDisabled() {
        for (HttpMobileBillingClient c : new HttpMobileBillingClient[]{
                new HttpMobileBillingClient("", "", om),
                new HttpMobileBillingClient(DEAD_BASE, "", om),
                new HttpMobileBillingClient("", "s", om)}) {
            assertEquals(MobileBillingKind.DISABLED,
                    assertThrows(MobileBillingException.class, () -> c.resolveAccountId("13900000000", null, false)).getKind());
            assertEquals(MobileBillingKind.DISABLED,
                    assertThrows(MobileBillingException.class, () -> c.balance("acct-x")).getKind());
            assertEquals(MobileBillingKind.DISABLED,
                    assertThrows(MobileBillingException.class, () -> c.createRecharge("acct-x", 5000, "idem-0001")).getKind());
            assertEquals(MobileBillingKind.DISABLED,
                    assertThrows(MobileBillingException.class, () -> c.queryRecharge("acct-x", "no-1")).getKind());
        }
    }

    @Test
    @DisplayName("官网连不上：UNAVAILABLE，绝不退化成 NOT_FOUND / 余额 0")
    void unreachableUpstreamIsUnavailable() {
        HttpMobileBillingClient c = new HttpMobileBillingClient(DEAD_BASE, "secret", om);
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class, () -> c.balance("acct-x")).getKind());
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class, () -> c.createRecharge("acct-x", 5000, "idem-0001")).getKind());
    }

    @Test
    @DisplayName("resolve 的 phone/email 必须恰好给一个：给两个或都不给是调用方编程错误")
    void resolveRequiresExactlyOneIdentity() {
        HttpMobileBillingClient c = new HttpMobileBillingClient(DEAD_BASE, "secret", om);
        assertThrows(IllegalStateException.class, () -> c.resolveAccountId("13900000000", "a@example.com", false));
        assertThrows(IllegalStateException.class, () -> c.resolveAccountId(null, null, false));
        assertThrows(IllegalStateException.class, () -> c.resolveAccountId("  ", " ", false));
    }

    // ==================== create 位（复审 C1） ====================

    @Test
    @DisplayName("resolve 把 create 位显式上行：读路径必须是 false，别指望官网的默认值")
    void resolveSendsCreateFlagExplicitly() {
        HttpMobileBillingClient c = stubbed(200, "{\"accountId\":\"acct-1\"}");

        assertEquals("acct-1", c.resolveAccountId("13900000000", null, false));
        assertTrue(lastRequest.get().contains("\"create\":false"), lastRequest.get());

        assertEquals("acct-1", c.resolveAccountId("13900000000", null, true));
        assertTrue(lastRequest.get().contains("\"create\":true"), lastRequest.get());
    }

    // ==================== 404 的两类（复审 C3） ====================

    @Test
    @DisplayName("空体 404 = 鉴权/配置失败：UNAVAILABLE，绝不是「没有账户」")
    void emptyBody404IsUnavailableNotNotFound() {
        HttpMobileBillingClient c = stubbed(404, null);
        MobileBillingException e =
                assertThrows(MobileBillingException.class, () -> c.balance("acct-x"));
        assertEquals(MobileBillingKind.UNAVAILABLE, e.getKind());
    }

    @Test
    @DisplayName("带 body 的 404 = 真业务查无此物：NOT_FOUND，machineError 留给日志")
    void jsonBody404IsNotFound() {
        HttpMobileBillingClient c = stubbed(404, "{\"error\":\"account_not_found\"}");
        MobileBillingException e =
                assertThrows(MobileBillingException.class, () -> c.balance("acct-x"));
        assertEquals(MobileBillingKind.NOT_FOUND, e.getKind());
        assertEquals("account_not_found", e.getMachineError());
    }

    @Test
    @DisplayName("404 回了一页 HTML（反代/网关）：按不可解析处理，仍是 UNAVAILABLE")
    void html404IsUnavailable() {
        HttpMobileBillingClient c = stubbed(404, "<html><body>404 Not Found</body></html>");
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class, () -> c.balance("acct-x")).getKind());
    }

    // ==================== 409 与 outTradeNo（复审 C4） ====================

    @Test
    @DisplayName("409 order_already_paid：ALREADY_PAID 且把 outTradeNo 带出来")
    void alreadyPaidCarriesOutTradeNo() {
        HttpMobileBillingClient c = stubbed(409,
                "{\"error\":\"order_already_paid\",\"outTradeNo\":\"RECHARGE202609040001\"}");
        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 5000, "idem-0001"));
        assertEquals(MobileBillingKind.ALREADY_PAID, e.getKind());
        assertEquals("RECHARGE202609040001", e.getOutTradeNo());
    }

    @Test
    @DisplayName("409 idempotency_conflict：IDEMPOTENCY_CONFLICT 且带 outTradeNo")
    void idempotencyConflictCarriesOutTradeNo() {
        HttpMobileBillingClient c = stubbed(409,
                "{\"error\":\"idempotency_conflict\",\"outTradeNo\":\"RECHARGE202609040002\"}");
        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 5000, "idem-0001"));
        assertEquals(MobileBillingKind.IDEMPOTENCY_CONFLICT, e.getKind());
        assertEquals("RECHARGE202609040002", e.getOutTradeNo());
    }

    @Test
    @DisplayName("其余 4xx → REJECTED；5xx → UNAVAILABLE")
    void otherFailuresKeepTheirOwnKind() {
        HttpMobileBillingClient rejected = stubbed(400, "{\"error\":\"phone_not_supported_on_site\"}");
        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> rejected.resolveAccountId("13900000000", null, false));
        assertEquals(MobileBillingKind.REJECTED, e.getKind());
        assertEquals("phone_not_supported_on_site", e.getMachineError());

        HttpMobileBillingClient down = stubbed(500, "{\"error\":\"internal_error\"}");
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class, () -> down.balance("acct-x")).getKind());
    }

    @Test
    @DisplayName("plan 是 paid/free 的计费档位，不是套餐名；上游不给才是 null")
    void planIsBillingTierNotPlanName() {
        assertEquals("paid", stubbed(200,
                "{\"balanceCents\":123,\"currency\":\"CNY\",\"plan\":\"paid\"}").balance("a").plan());
        assertEquals("free", stubbed(200,
                "{\"balanceCents\":0,\"currency\":\"CNY\",\"plan\":\"free\"}").balance("a").plan());
        assertNull(stubbed(200, "{\"balanceCents\":0,\"currency\":\"CNY\"}").balance("a").plan());
    }
}
