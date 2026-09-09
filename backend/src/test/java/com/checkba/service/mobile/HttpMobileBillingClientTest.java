// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** 桩服务收到的请求次数，用来断言 wxvp 不重试。 */
    private final AtomicInteger hits = new AtomicInteger();
    /** 置 true 时桩服务读完请求体直接断开、不回任何响应，模拟「请求已到达但响应丢了」的网络失败。 */
    private final AtomicBoolean stubDrop = new AtomicBoolean(false);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/account", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            hits.incrementAndGet();
            if (stubDrop.get()) {
                exchange.close();
                return;
            }
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
                    assertThrows(MobileBillingException.class,
                            () -> c.createRecharge("acct-x", 5000, "idem-0001", null, null, null)).getKind());
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
                assertThrows(MobileBillingException.class,
                        () -> c.createRecharge("acct-x", 5000, "idem-0001", null, null, null)).getKind());
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
                () -> c.createRecharge("acct-x", 5000, "idem-0001", null, null, null));
        assertEquals(MobileBillingKind.ALREADY_PAID, e.getKind());
        assertEquals("RECHARGE202609040001", e.getOutTradeNo());
    }

    @Test
    @DisplayName("409 idempotency_conflict：IDEMPOTENCY_CONFLICT 且带 outTradeNo")
    void idempotencyConflictCarriesOutTradeNo() {
        HttpMobileBillingClient c = stubbed(409,
                "{\"error\":\"idempotency_conflict\",\"outTradeNo\":\"RECHARGE202609040002\"}");
        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 5000, "idem-0001", null, null, null));
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

    // ==================== 小程序虚拟支付（dev-board#427） ====================

    @Test
    @DisplayName("channel=wxvp：三个字段上行，signData/paySig/signature 解回记录")
    void wxvpFieldsGoUpAndSignaturesComeBack() {
        HttpMobileBillingClient c = stubbed(200,
                "{\"present\":\"virtual\",\"outTradeNo\":\"OT-vp-1\",\"amountCents\":1000,"
                        + "\"signData\":\"{}\",\"paySig\":\"aa11\",\"signature\":\"bb22\"}");

        MobileBillingClient.RechargeOrder order = c.createRecharge("acct-x", 1000, "idem-0001",
                "wxvp", "credits_cny_10", "0a1b2c3d4e");

        assertEquals("virtual", order.present());
        assertEquals("aa11", order.paySig());
        assertEquals("bb22", order.signature());
        assertNull(order.codeUrl());
        String sent = lastRequest.get();
        assertTrue(sent.contains("\"channel\":\"wxvp\""), sent);
        assertTrue(sent.contains("\"productId\":\"credits_cny_10\""), sent);
        assertTrue(sent.contains("\"wxCode\":\"0a1b2c3d4e\""), sent);
    }

    @Test
    @DisplayName("channel=wxvp 网络失败只发一次（wxCode 一次性，重试同一 body 必败）；默认通道仍重试一次")
    void wxvpDoesNotRetryOnNetworkFailure() {
        HttpMobileBillingClient c = stubbed(200, "{}");
        stubDrop.set(true);

        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 1000, "idem-0001", "wxvp", "credits_cny_10", "0a1b2c3d4e"));
        assertEquals(MobileBillingKind.UNAVAILABLE, e.getKind());
        assertEquals(1, hits.get(), "wxvp 不许重试");

        hits.set(0);
        assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 5000, "idem-0002", null, null, null));
        assertEquals(2, hits.get(), "默认通道维持第一期的重试一次");
    }

    @Test
    @DisplayName("不带 channel（第一期形态）：通道三兄弟一个都不上行")
    void defaultChannelSendsNoChannelFields() {
        HttpMobileBillingClient c = stubbed(200,
                "{\"present\":\"qrcode\",\"outTradeNo\":\"OT-1\",\"amountCents\":5000,\"codeUrl\":\"weixin://x\"}");

        assertEquals("weixin://x",
                c.createRecharge("acct-x", 5000, "idem-0001", null, null, null).codeUrl());
        String sent = lastRequest.get();
        assertFalse(sent.contains("channel"), sent);
        assertFalse(sent.contains("productId"), sent);
        assertFalse(sent.contains("wxCode"), sent);
    }

    @Test
    @DisplayName("400 product_mismatch：REJECTED，但给的是「请更新小程序」而不是「联系客服」")
    void productMismatchHasItsOwnMessage() {
        HttpMobileBillingClient c = stubbed(400, "{\"error\":\"product_mismatch\"}");

        MobileBillingException e = assertThrows(MobileBillingException.class,
                () -> c.createRecharge("acct-x", 9900, "idem-0001", "wxvp", "credits_cny_10", "0a1b"));

        assertEquals(MobileBillingKind.REJECTED, e.getKind());
        assertEquals("product_mismatch", e.getMachineError());
        assertEquals("充值档位与金额不符，请更新小程序后重试", e.getMessage());
    }

    // ==================== 注销传导（dev-board#434） ====================

    @Test
    @DisplayName("delete-account 200 {deleted:true}：删成功，action 与 accountId 上行")
    void deleteAccountSuccess() {
        HttpMobileBillingClient c = stubbed(200, "{\"deleted\":true}");

        MobileBillingClient.DeleteAccountResult r = c.deleteAccount("acct-x");

        assertTrue(r.deleted());
        assertNull(r.blocker());
        assertTrue(lastRequest.get().contains("\"action\":\"delete-account\""), lastRequest.get());
        assertTrue(lastRequest.get().contains("\"accountId\":\"acct-x\""), lastRequest.get());
    }

    @Test
    @DisplayName("delete-account 200 {deleted:false}：带 blocker 与官网给的可读 message 回来")
    void deleteAccountBlockedCarriesReason() {
        HttpMobileBillingClient c = stubbed(200,
                "{\"deleted\":false,\"blocker\":\"refundable_balance\",\"message\":\"账上还有可退余额\"}");

        MobileBillingClient.DeleteAccountResult r = c.deleteAccount("acct-x");

        assertFalse(r.deleted());
        assertEquals("refundable_balance", r.blocker());
        assertEquals("账上还有可退余额", r.message());
    }

    @Test
    @DisplayName("delete-account 带 body 的 404：官网本来就没有这个账户 = 已删，不是失败")
    void deleteAccountNotFoundCountsAsDeleted() {
        HttpMobileBillingClient c = stubbed(404, "{\"error\":\"account_not_found\"}");
        assertTrue(c.deleteAccount("acct-x").deleted());
    }

    @Test
    @DisplayName("delete-account 空体 404 / 5xx / 连不上：一律抛 UNAVAILABLE，绝不当成「已经删了」")
    void deleteAccountFailuresAreLoud() {
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class,
                        () -> stubbed(404, null).deleteAccount("acct-x")).getKind());
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class,
                        () -> stubbed(502, "{\"error\":\"key_disable_failed\"}").deleteAccount("acct-x")).getKind());
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class,
                        () -> new HttpMobileBillingClient(DEAD_BASE, "secret", om).deleteAccount("acct-x"))
                        .getKind());
        assertEquals(MobileBillingKind.DISABLED,
                assertThrows(MobileBillingException.class,
                        () -> new HttpMobileBillingClient("", "", om).deleteAccount("acct-x")).getKind());
    }

    // ==================== 微信手机号一键登录（dev-board#534） ====================

    @Test
    @DisplayName("wx-phone 成功：action/code 上行，回大陆手机号")
    void wxPhoneSuccess() {
        HttpMobileBillingClient c = stubbed(200, "{\"phone\":\"13800000001\"}");

        assertEquals("13800000001", c.wxPhone("0a1b2c3d4e"));

        String sent = lastRequest.get();
        assertTrue(sent.contains("\"action\":\"wx-phone\""), sent);
        assertTrue(sent.contains("\"code\":\"0a1b2c3d4e\""), sent);
    }

    @Test
    @DisplayName("wx-phone 的三种失败各自翻成登录场景的话，绝不共用计费那句「联系客服」")
    void wxPhoneFailuresSpeakLoginLanguage() {
        // 本机没配 base-url/secret：短路，且这一条的文案是登录场景的，不是「未开通统一账户充值」
        MobileBillingException localOff = assertThrows(MobileBillingException.class,
                () -> new HttpMobileBillingClient("", "", om).wxPhone("0a1b"));
        assertEquals(MobileBillingKind.DISABLED, localOff.getKind());
        assertTrue(localOff.getMessage().contains("未开通微信一键登录"), localOff.getMessage());
        assertEquals(0, hits.get(), "未配置不许发请求");

        // 官网未配小程序 AppSecret：503 wx_not_configured 与本机没配是同一个结论
        MobileBillingException notConfigured = assertThrows(MobileBillingException.class,
                () -> stubbed(503, "{\"error\":\"wx_not_configured\"}").wxPhone("0a1b"));
        assertEquals(MobileBillingKind.DISABLED, notConfigured.getKind());
        assertTrue(notConfigured.getMessage().contains("未开通微信一键登录"), notConfigured.getMessage());

        // code 无效 → 401
        MobileBillingException expired = assertThrows(MobileBillingException.class,
                () -> stubbed(401, "{\"error\":\"invalid_wx_code\"}").wxPhone("0a1b"));
        assertEquals(MobileBillingKind.REJECTED, expired.getKind());
        assertEquals("invalid_wx_code", expired.getMachineError());
        assertTrue(expired.getMessage().contains("微信授权已过期"), expired.getMessage());

        // 非大陆号 → 400
        MobileBillingException region = assertThrows(MobileBillingException.class,
                () -> stubbed(400, "{\"error\":\"unsupported_region\"}").wxPhone("0a1b"));
        assertEquals(MobileBillingKind.REJECTED, region.getKind());
        assertTrue(region.getMessage().contains("仅支持中国大陆手机号"), region.getMessage());
    }

    @Test
    @DisplayName("wx-phone：code 一次性，网络失败只发一次；官网回的号形状不对按上游故障处理")
    void wxPhoneNeverRetriesAndValidatesShape() {
        HttpMobileBillingClient c = stubbed(200, "{}");
        stubDrop.set(true);
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingException.class, () -> c.wxPhone("0a1b")).getKind());
        assertEquals(1, hits.get(), "code 一次性，不许重试");

        stubDrop.set(false);
        for (String bad : new String[]{"{}", "{\"phone\":\"\"}", "{\"phone\":\"+8613800000001\"}"}) {
            assertEquals(MobileBillingKind.UNAVAILABLE,
                    assertThrows(MobileBillingException.class,
                            () -> stubbed(200, bad).wxPhone("0a1b")).getKind(),
                    bad);
        }
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
