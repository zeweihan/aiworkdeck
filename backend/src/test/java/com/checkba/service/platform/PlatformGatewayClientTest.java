package com.checkba.service.platform;

import com.checkba.service.account.AccountService;
import com.checkba.service.site.SiteProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 网关客户端：错误分类、幂等重试、文案红线。
 *
 * <p>这三样都是「错了不会当场报错、只会在真实用户身上出问题」的那类：
 * 错误分类错了会把用户踢下线，幂等键换了会扣两次钱，文案踩线会清掉本地会话。
 */
class PlatformGatewayClientTest {

    /** 记录每次出站的 transport 桩：可断言调了几次、幂等键是否一致。 */
    static class RecordingTransport implements PlatformGatewayTransport {
        record Call(String method, String url, String bearer, String idempotencyKey, String body, int timeout) {}

        final List<Call> calls = new ArrayList<>();
        final List<Reply> replies = new ArrayList<>();
        int cursor = 0;

        RecordingTransport reply(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        @Override
        public Reply send(String method, String url, String bearerKey, String idempotencyKey,
                          String jsonBody, int timeoutSeconds) {
            calls.add(new Call(method, url, bearerKey, idempotencyKey, jsonBody, timeoutSeconds));
            return cursor < replies.size() ? replies.get(cursor++) : new Reply(200, "{}");
        }
    }

    private RecordingTransport transport;
    private AccountService accountService;
    private SiteProfileService siteProfileService;
    private PlatformGatewayClient client;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        accountService = mock(AccountService.class);
        siteProfileService = mock(SiteProfileService.class);
        when(accountService.currentKeyOrNull()).thenReturn("awdk_test");
        when(siteProfileService.baseUrl()).thenReturn("https://www.aiworkdeck.com");
        client = new PlatformGatewayClient(transport, accountService, siteProfileService);
    }

    @Test
    @DisplayName("未连账户时不发请求，直接回 NOT_CONNECTED")
    void notConnectedDoesNotSend() {
        when(accountService.currentKeyOrNull()).thenReturn(null);
        GatewayException e = assertThrows(GatewayException.class,
                () -> client.call("search", "web", Map.of("query", "x"), 30));
        assertEquals(GatewayException.Kind.NOT_CONNECTED, e.getKind());
        // 发出去只会拿回 401，而 401 在桌面端会被判成凭据失效并清空已购权益缓存
        assertTrue(transport.calls.isEmpty(), "未连账户时不该发出任何请求");
    }

    @Test
    @DisplayName("成功：解析 data 与 billing")
    void successParsesBilling() {
        transport.reply(200, """
                {"ok":true,"data":{"webPages":{"value":[{"name":"t"}]}},
                 "billing":{"service":"search","op":"web","units":1,"unit":"call","chargedCents":30}}""");
        PlatformGatewayClient.Result r = client.call("search", "web", Map.of("query", "x"), 30);
        assertEquals(30, r.chargedCents());
        assertEquals("call", r.unit());
        assertEquals("t", r.data().path("webPages").path("value").get(0).path("name").asText());
    }

    @Test
    @DisplayName("网络失败会重试一次，且必须带同一个幂等键")
    void retriesWithSameIdempotencyKey() {
        transport.reply(PlatformGatewayTransport.Reply.NETWORK_FAILURE, null);
        transport.reply(200, """
                {"ok":true,"data":{},"billing":{"chargedCents":30,"units":1,"unit":"call"}}""");

        client.call("search", "web", Map.of("query", "x"), 30);

        assertEquals(2, transport.calls.size(), "网络失败应重试一次");
        // 换新键重试 = 放弃幂等保护 = 第一次若已在服务端扣费，这次就是扣两次
        assertEquals(transport.calls.get(0).idempotencyKey(), transport.calls.get(1).idempotencyKey(),
                "重试必须带同一个幂等键");
        assertNotNull(transport.calls.get(0).idempotencyKey(), "会扣费的调用必须带幂等键");
    }

    /**
     * 病灶：HttpPlatformGatewayTransport 捕获 InterruptedException 后会恢复中断标志
     * （{@code Thread.currentThread().interrupt()}），但返回的 Reply 与其它网络失败长得
     * 一模一样——PlatformGatewayClient 分不出"这次失败是因为调用方线程被要求停下"还是
     * "单纯网络抖动"，于是仍然照常重试一次：在一个已经被要求停止的线程上又发起一次
     * 最长可以再等 timeoutSeconds 的阻塞调用，拖慢优雅关闭。
     *
     * <p>修法：重试前查一下当前线程的中断标志（peek，不清）；已经被中断就不重试，
     * 直接按网络失败处理，把控制权尽快还给调用方。
     */
    @Test
    @DisplayName("第一次调用后线程已被中断时，不再发起重试")
    void doesNotRetryWhenThreadAlreadyInterrupted() {
        transport = new RecordingTransport() {
            @Override
            public Reply send(String method, String url, String bearerKey, String idempotencyKey,
                              String jsonBody, int timeoutSeconds) {
                Reply r = super.send(method, url, bearerKey, idempotencyKey, jsonBody, timeoutSeconds);
                // 模拟 HttpPlatformGatewayTransport 捕获 InterruptedException 后的真实行为：
                // 恢复中断标志、把这次结果当网络失败返回。
                Thread.currentThread().interrupt();
                return r;
            }
        };
        transport.reply(PlatformGatewayTransport.Reply.NETWORK_FAILURE, null);
        client = new PlatformGatewayClient(transport, accountService, siteProfileService);

        try {
            // 只关心发出去几次请求，不关心这次失败具体报成哪种 GatewayException
            try {
                client.call("search", "web", Map.of("query", "x"), 30);
            } catch (GatewayException ignored) {
            }
            assertEquals(1, transport.calls.size(), "线程已经被要求停止，不该再发第二次请求");
        } finally {
            assertTrue(Thread.interrupted(), "中断标志本该留给线程原本的所有者处理（顺带清掉，不污染后续用例）");
        }
    }

    @Test
    @DisplayName("两次网络失败后回 GATEWAY_UNREACHABLE，且文案明说不是用户的网络问题")
    void unreachableSaysNotYourNetwork() {
        transport.reply(PlatformGatewayTransport.Reply.NETWORK_FAILURE, null);
        transport.reply(PlatformGatewayTransport.Reply.NETWORK_FAILURE, null);

        GatewayException e = assertThrows(GatewayException.class,
                () -> client.call("search", "web", Map.of("query", "x"), 30));
        assertEquals(GatewayException.Kind.GATEWAY_UNREACHABLE, e.getKind());
        // 账户通道那句「请检查网络后重试」会让用户去重启路由器，而真实原因往往是我们在发版
        assertTrue(e.getMessage().contains("不是你的网络问题") || e.getMessage().contains("not a problem with your network"),
                "网关不可达的文案必须明说这不是用户的网络问题，实际为：" + e.getMessage());
        assertTrue(e.suggestsByok(), "我们挂了的时候，自备 Key 是一条有意义的出路");
    }

    @Test
    @DisplayName("三类故障分开：未开放 / 上游挂 / 余额不足")
    void classifiesThreeFailureKinds() {
        transport.reply(409, "{\"error\":\"service_disabled\",\"message\":\"暂未开放\"}");
        assertEquals(GatewayException.Kind.SERVICE_DISABLED,
                assertThrows(GatewayException.class, () -> client.call("qichacha", "search", Map.of(), 30)).getKind());

        transport.reply(502, "{\"error\":\"upstream_failed\",\"message\":\"上游 500\"}");
        assertEquals(GatewayException.Kind.UPSTREAM_FAILED,
                assertThrows(GatewayException.class, () -> client.call("search", "web", Map.of(), 30)).getKind());

        transport.reply(409, "{\"error\":\"no_credits\",\"message\":\"Credits 余额不足，到官网充值后即可继续\"}");
        GatewayException noCredits = assertThrows(GatewayException.class,
                () -> client.call("search", "web", Map.of(), 30));
        assertEquals(GatewayException.Kind.NO_CREDITS, noCredits.getKind());
    }

    @Test
    @DisplayName("撞上用户自设的花费上限是可恢复的确认，不是「预期外的状态 409」")
    void budgetExceededIsItsOwnKind() {
        // 不认这个码的话它会落到按状态码分类的默认分支，变成 MALFORMED——
        // 一个「确认一下就能继续」的信封被表达成故障，用户只会以为功能坏了
        transport.reply(409, "{\"error\":\"budget_exceeded\",\"message\":\"本次任务已花费 12.00 Credits\"}");
        GatewayException e = assertThrows(GatewayException.class,
                () -> client.call("search", "web", Map.of(), 30));
        assertEquals(GatewayException.Kind.BUDGET_EXCEEDED, e.getKind());
        // 这是用户自己设的闸拦了自己，不是我们的故障：摆「改用自己的 Key」既答非所问，
        // 照做还会让他绕开刚设的上限
        assertFalse(e.suggestsByok());
    }

    @Test
    @DisplayName("除「Key 无效」与我们自己的 bug 外，都要摆出「改用自己的 Key」的出路")
    void byokIsOfferedWhereverItHelps() {
        // 用试用码解锁、根本不打算连账户的用户，自备 Key 是他唯一的出路——
        // 只提示「去连账户」等于把这批人（README 公开试用码是主要获客入口）堵死
        assertTrue(new GatewayException(GatewayException.Kind.NOT_CONNECTED, "x").suggestsByok());
        assertTrue(new GatewayException(GatewayException.Kind.NO_CREDITS, "x").suggestsByok());
        assertTrue(new GatewayException(GatewayException.Kind.SERVICE_DISABLED, "x").suggestsByok());
        assertTrue(new GatewayException(GatewayException.Kind.GATEWAY_UNREACHABLE, "x").suggestsByok());
        assertTrue(new GatewayException(GatewayException.Kind.UPSTREAM_FAILED, "x").suggestsByok());
        // 这里结论已经明确（Key 无效或被撤销），再塞第二个建议只会让用户不知道该修哪个
        assertFalse(new GatewayException(GatewayException.Kind.UNAUTHORIZED, "x").suggestsByok());
    }

    @Test
    @DisplayName("只有 401/403 判成凭据失效，5xx 绝不判成 UNAUTHORIZED")
    void onlyAuthErrorsAreUnauthorized() {
        transport.reply(401, "{}");
        assertEquals(GatewayException.Kind.UNAUTHORIZED,
                assertThrows(GatewayException.class, () -> client.call("search", "web", Map.of(), 30)).getKind());

        transport.reply(503, "{}");
        assertEquals(GatewayException.Kind.SERVICE_DISABLED,
                assertThrows(GatewayException.class, () -> client.call("search", "web", Map.of(), 30)).getKind());
    }

    @Test
    @DisplayName("所有网关文案都不含「登录」「未授权」「请先」——命中即被前端判成掉线并清会话")
    void messagesNeverLookLikeLogout() {
        List<PlatformGatewayTransport.Reply> cases = List.of(
                new PlatformGatewayTransport.Reply(409, "{\"error\":\"no_credits\"}"),
                new PlatformGatewayTransport.Reply(409, "{\"error\":\"service_disabled\"}"),
                new PlatformGatewayTransport.Reply(409, "{\"error\":\"budget_exceeded\"}"),
                new PlatformGatewayTransport.Reply(502, "{\"error\":\"upstream_failed\"}"),
                new PlatformGatewayTransport.Reply(503, "{}"),
                new PlatformGatewayTransport.Reply(401, "{}"),
                new PlatformGatewayTransport.Reply(418, "{}"),
                new PlatformGatewayTransport.Reply(PlatformGatewayTransport.Reply.NETWORK_FAILURE, null));

        for (PlatformGatewayTransport.Reply canned : cases) {
            RecordingTransport t = new RecordingTransport();
            // 网络失败那条会重试一次，两次都要有回应
            t.replies.add(canned);
            t.replies.add(canned);
            PlatformGatewayClient c = new PlatformGatewayClient(t, accountService, siteProfileService);
            GatewayException e = assertThrows(GatewayException.class,
                    () -> c.call("search", "web", Map.of(), 30));
            for (String forbidden : List.of("登录", "未授权", "请先")) {
                assertFalse(e.getMessage().contains(forbidden),
                        "状态 " + canned.status() + " 的文案含「" + forbidden + "」，会被 api.js 判成掉线：" + e.getMessage());
            }
        }

        // 未连账户那条不经过 transport，单独验一遍
        when(accountService.currentKeyOrNull()).thenReturn(null);
        GatewayException e = assertThrows(GatewayException.class,
                () -> client.call("search", "web", Map.of(), 30));
        for (String forbidden : List.of("登录", "未授权", "请先")) {
            assertFalse(e.getMessage().contains(forbidden), "未连账户文案含「" + forbidden + "」：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("超时按调用方给的秒数传下去，不是账户通道的 5 秒")
    void usesCallerTimeout() {
        transport.reply(200, "{\"ok\":true,\"data\":{},\"billing\":{}}");
        client.call("ocr", "recognize", Map.of(), 60);
        assertEquals(60, transport.calls.get(0).timeout(),
                "OCR/TTS/听悟建任务超过 5 秒是常态，沿用账户通道的超时等于自造重复扣费窗口");
    }

    @Test
    @DisplayName("单价表是只读调用，不带幂等键")
    void pricingCarriesNoIdempotencyKey() {
        transport.reply(200, "{\"pricing\":[],\"balanceCents\":100,\"pendingHoldCents\":0}");
        client.getPricing(10);
        assertEquals("GET", transport.calls.get(0).method());
        assertNull(transport.calls.get(0).idempotencyKey(), "不扣费的端点不该占幂等键");
    }
}
