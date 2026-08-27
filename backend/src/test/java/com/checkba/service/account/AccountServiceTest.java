package com.checkba.service.account;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定账户连接契约（商业化改造 PR-B）：
 * - connect 校验通过才落盘，且文件权限 0600（存着明文 awdk_ Key）；
 * - 失败分类：网络不可达 / 鉴权失败 / 业务冲突各走各的分支，5xx 归网络不能清连接；
 * - 任何错误信息都是中文且**不含 Key 明文**；
 * - 官网契约文档记录的两处偏差：ledger 是 {entries:[...]} 包裹，不是裸数组。
 */
class AccountServiceTest {

    private static final String KEY = "awdk_" + "S3cretKeyMaterial0123456789abcdefghij";

    @TempDir
    Path tempDir;

    /** 可编排的出站桩：按入队顺序返回，同时记录收到的请求。 */
    static class StubTransport implements AccountTransport {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        String lastBearer;

        StubTransport enqueue(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        StubTransport enqueueNetworkFailure() {
            replies.add(new Reply(Reply.NETWORK_FAILURE, null));
            return this;
        }

        @Override
        public Reply send(String method, String url, String bearerKey, String jsonBody) {
            calls.add(method + " " + url);
            bodies.add(jsonBody == null ? "" : jsonBody);
            lastBearer = bearerKey;
            if (replies.isEmpty()) {
                throw new AssertionError("桩没有为 " + method + " " + url + " 准备响应");
            }
            return replies.poll();
        }
    }

    private StubTransport transport;

    private AccountService service() {
        return new AccountService(
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                tempDir.toString(), transport, null);
    }

    private AccountService connected() {
        transport = new StubTransport().enqueue(200,
                "{\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\",\"balanceCents\":1980,\"plan\":\"paid\"}");
        AccountService service = service();
        service.connect(KEY);
        return service;
    }

    // ==================== 账户登录（手机号/邮箱直登） ====================

    private static final String ME = "{\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\"}";

    @Test
    @DisplayName("发验证码：转发到官网 sms-login/send-code，且不带 Bearer（登录阶段还没有 Key）")
    void loginSendCodeForwards() {
        transport = new StubTransport().enqueue(200, "{\"ok\":true}");
        service().sendLoginCode("13800138000", null);
        assertEquals("POST https://www.aiworkdeck.com/api/auth/sms-login/send-code", transport.calls.get(0));
        assertTrue(transport.bodies.get(0).contains("13800138000"), transport.bodies.get(0));
        assertNull(transport.lastBearer, "登录阶段还没有 Key，不该带 Authorization");
    }

    @Test
    @DisplayName("邮箱验证码登录：intl 的验证码账号没有口令，少了这条路他们永远连不上桌面端")
    void emailCodeLoginExchangesKey() {
        transport = new StubTransport()
                .enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":false}")
                .enqueue(200, ME);
        AccountService service = service();
        service.loginWithEmailCode("alice@example.com", "123456");
        assertEquals("POST https://www.aiworkdeck.com/api/auth/exchange-key", transport.calls.get(0));
        assertTrue(transport.bodies.get(0).contains("alice@example.com"), transport.bodies.get(0));
        assertTrue(transport.bodies.get(0).contains("123456"), transport.bodies.get(0));
    }

    @Test
    @DisplayName("邮箱发码走 mail-login/send-code，且同样透传人机验证 token")
    void emailSendCodeForwards() {
        transport = new StubTransport().enqueue(200, "{\"ok\":true}");
        service().sendLoginCodeByEmail("alice@example.com", "tok-1");
        assertEquals("POST https://www.aiworkdeck.com/api/auth/mail-login/send-code", transport.calls.get(0));
        assertTrue(transport.bodies.get(0).contains("tok-1"), transport.bodies.get(0));
    }

    @Test
    @DisplayName("人机验证 token 必须原样透传——这个请求体是本类拼的，漏掉它桌面端就永远过不了官网那道闸")
    void loginSendCodeForwardsCaptchaToken() {
        transport = new StubTransport().enqueue(200, "{\"ok\":true}");
        service().sendLoginCode("13800138000", "the-captcha-token");
        assertTrue(transport.bodies.get(0).contains("the-captcha-token"),
                "官网启用人机验证后，不带 token 就是 403：" + transport.bodies.get(0));
    }

    @Test
    @DisplayName("人机验证的文案用本地双语，不用官网那句英文——否则中文界面上会冒出英文")
    void captchaMessageIsLocalizedNotUpstreamEnglish() {
        transport = new StubTransport().enqueue(403,
                "{\"error\":\"captcha_failed\",\"message\":\"Verification failed, please try again\"}");
        AccountException e = assertThrows(AccountException.class,
                () -> service().sendLoginCode("13800138000", "bad"));
        assertNotEquals("Verification failed, please try again", e.getMessage(),
                "官网只有英文文案，原样透出去就是中文界面上的英文句子");
        assertTrue(e.getMessage().contains("安全验证") || e.getMessage().contains("security"),
                "应当是本地双语文案：" + e.getMessage());
    }

    @Test
    @DisplayName("官网拒绝人机验证时归为 CONFLICT，不是 UNAUTHORIZED——后者会让上层去清一个还不存在的连接")
    void captchaFailureIsConflictNotUnauthorized() {
        transport = new StubTransport().enqueue(403, "{\"error\":\"captcha_failed\",\"message\":\"nope\"}");
        AccountException e = assertThrows(AccountException.class,
                () -> service().sendLoginCode("13800138000", "bad"));
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
    }

    @Test
    @DisplayName("拿不到官网的人机验证配置时按「未启用」处理，而不是让人登不进去")
    void captchaConfigFallsBackToDisabled() {
        transport = new StubTransport().enqueue(500, "boom");
        assertNull(service().captchaConfig().get("provider"),
                "配置读不到就渲染不出控件；为此把人挡在门外不划算，发码本身还有限流兜着");
    }

    @Test
    @DisplayName("手机号登录：换 Key 后自动连接并落盘，用户全程看不到 Key")
    void phoneLoginExchangesKeyAndConnects() {
        transport = new StubTransport()
                .enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":true,\"mustBindPhone\":false}")
                .enqueue(200, ME);
        AccountService service = service();
        Map<String, Object> result = service.loginWithPhone("13800138000", "123456");

        assertEquals("POST https://www.aiworkdeck.com/api/auth/exchange-key", transport.calls.get(0));
        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(1),
                "换到 Key 之后必须复用 connect 去校验，而不是直接落盘");
        assertEquals(true, result.get("connected"));
        assertEquals(true, result.get("isNewUser"));
        assertEquals("hanzewei", result.get("username"));
        assertFalse(result.toString().contains(KEY), "返回体不允许出现 Key 明文: " + result);
        assertTrue(Files.exists(tempDir.resolve("account.json")));
        assertTrue(service.isConnected());
    }

    @Test
    @DisplayName("换 Key 请求带上设备名：官网账户页「已连接的设备」靠它显示")
    void exchangeKeyRequestIncludesDeviceName() {
        transport = new StubTransport()
                .enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":true}")
                .enqueue(200, ME);
        service().loginWithPhone("13800138000", "123456");
        assertTrue(transport.bodies.get(0).contains("\"deviceName\""), transport.bodies.get(0));
    }

    @Test
    @DisplayName("deviceName()：非空、不超 64 字符、带 os 标签")
    void deviceNameIsWellFormed() {
        String name = AccountService.deviceName();
        assertNotNull(name);
        assertFalse(name.isBlank());
        assertTrue(name.length() <= 64, name);
        assertTrue(name.contains("Mac") || name.contains("Windows") || name.contains("Linux") || name.contains("Desktop"),
                name);
    }

    @Test
    @DisplayName("口令登录：国际站主路径，同样换 Key 并连接")
    void passwordLoginExchangesKey() {
        transport = new StubTransport()
                .enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":false}")
                .enqueue(200, ME);
        Map<String, Object> result = service().loginWithPassword("hanzewei", "pw12345678");
        assertEquals("POST https://www.aiworkdeck.com/api/auth/exchange-key", transport.calls.get(0));
        assertTrue(transport.bodies.get(0).contains("hanzewei"));
        assertFalse(transport.bodies.get(0).contains("13800138000"));
        assertEquals(true, result.get("connected"));
        assertEquals(false, result.get("isNewUser"));
    }

    @Test
    @DisplayName("验证码错误：透传官网文案，且绝不落盘")
    void loginFailureSurfacesWebsiteMessageAndPersistsNothing() {
        transport = new StubTransport()
                .enqueue(401, "{\"error\":\"invalid_code\",\"message\":\"验证码错误或已过期\"}");
        AccountService service = service();
        AccountException e = assertThrows(AccountException.class,
                () -> service.loginWithPhone("13800138000", "000000"));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertEquals("验证码错误或已过期", e.getMessage(),
                "登录阶段的 401 是验证码错，不能套用「Key 无效或已被吊销」那句");
        assertFalse(Files.exists(tempDir.resolve("account.json")), "登录失败不得落盘");
        assertFalse(service.isConnected());
    }

    @Test
    @DisplayName("官网没给 message：按 error code 出兜底文案，不是一律「操作失败」")
    void loginFailureFallsBackByErrorCode() {
        transport = new StubTransport().enqueue(401, "{\"error\":\"invalid_credentials\"}");
        AccountException e = assertThrows(AccountException.class,
                () -> service().loginWithPassword("hanzewei", "wrong"));
        assertTrue(e.getMessage().contains("账号或密码"), e.getMessage());
    }

    @Test
    @DisplayName("超过补绑硬期限：归 CONFLICT 不是 UNAUTHORIZED，且文案给出人工通道")
    void loginBlockedByBindingDeadlineIsConflict() {
        transport = new StubTransport().enqueue(403,
                "{\"error\":\"phone_binding_required\",\"message\":\"该账户尚未绑定手机号，且已超过绑定期限。请邮件联系 hi@aiworkdeck.com 处理\"}");
        AccountException e = assertThrows(AccountException.class,
                () -> service().loginWithPassword("olduser", "pw12345678"));
        // UNAUTHORIZED 会让上层去清本地连接，而这时候根本还没有连接可清
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertTrue(e.getMessage().contains("hi@aiworkdeck.com"), e.getMessage());
    }

    @Test
    @DisplayName("登录时断网：归 NETWORK，不留下半连接状态")
    void loginNetworkFailureIsNetworkKind() {
        transport = new StubTransport().enqueueNetworkFailure();
        AccountService service = service();
        AccountException e = assertThrows(AccountException.class,
                () -> service.loginWithPhone("13800138000", "123456"));
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertFalse(service.isConnected());
    }

    @Test
    @DisplayName("官网回 200 但没带 key：判 MALFORMED，不能当成功")
    void loginWithoutKeyIsMalformed() {
        transport = new StubTransport().enqueue(200, "{\"isNewUser\":true}");
        AccountException e = assertThrows(AccountException.class,
                () -> service().loginWithPhone("13800138000", "123456"));
        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
    }

    // ==================== connect ====================

    @Test
    @DisplayName("连接成功：落盘 + status 带用户名，且绝不返回 Key 明文")
    void connectPersistsAndNeverExposesKey() {
        AccountService service = connected();

        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(0));
        assertEquals(KEY, transport.lastBearer, "出站请求必须带 Bearer <key>");

        Map<String, Object> status = service.status();
        assertEquals(true, status.get("connected"));
        assertEquals("hanzewei", status.get("username"));
        assertEquals("韩泽伟", status.get("displayName"));
        assertNotNull(status.get("connectedAt"));
        assertFalse(status.toString().contains(KEY), "status 不允许出现 Key 明文: " + status);
        assertTrue(String.valueOf(status.get("keyMasked")).startsWith("awdk_****"));

        assertTrue(Files.exists(tempDir.resolve("account.json")));
        // 新实例（模拟重启）从盘上恢复
        transport = new StubTransport();
        assertTrue(service().isConnected());
    }

    @Test
    @DisplayName("account.json 存明文 Key，权限必须收敛为 0600")
    void accountFileIsOwnerOnly() throws Exception {
        connected();
        Path file = tempDir.resolve("account.json");
        Assumptions.assumeTrue(
                Files.getFileAttributeView(file, PosixFileAttributeView.class) != null, "非 POSIX 文件系统跳过");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    @DisplayName("401：分类为鉴权失败，中文提示，不落盘")
    void unauthorizedIsClassifiedAndNotPersisted() {
        transport = new StubTransport().enqueue(401, "{\"error\":\"unauthorized\"}");
        AccountException e = assertThrows(AccountException.class, () -> service().connect(KEY));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertTrue(e.getMessage().contains("账户 Key 无效"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY), "错误信息不允许出现 Key 明文");
        assertFalse(Files.exists(tempDir.resolve("account.json")));
    }

    @Test
    @DisplayName("连接超时/断网：分类为网络不可达，中文提示")
    void networkFailureIsClassified() {
        transport = new StubTransport().enqueueNetworkFailure();
        AccountException e = assertThrows(AccountException.class, () -> service().connect(KEY));
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertTrue(e.getMessage().contains("网络"), e.getMessage());
    }

    @Test
    @DisplayName("5xx 归为网络不可达：服务器故障不等于凭据失效，不能据此清连接")
    void serverErrorIsNetworkNotUnauthorized() {
        AccountService service = connected();
        transport.enqueue(503, "gateway down");
        AccountException e = assertThrows(AccountException.class, service::fetchEntitlements);
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertTrue(service.isConnected(), "5xx 之后本地连接必须还在");
    }

    @Test
    @DisplayName("Key 格式不对：本地直接拒绝，一个请求都不发")
    void malformedKeyRejectedWithoutRequest() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class, () -> service().connect("sk-not-an-awdk"));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertTrue(e.getMessage().contains("awdk_"), e.getMessage());
        assertTrue(transport.calls.isEmpty(), "格式不对不该产生出站请求");
    }

    @Test
    @DisplayName("未连接时拉数据：分类为 NOT_CONNECTED，请求不发出")
    void notConnectedShortCircuits() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class, () -> service().fetchLedger());
        assertEquals(AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(transport.calls.isEmpty());
    }

    // ==================== disconnect ====================

    @Test
    @DisplayName("断开：清除本地凭据")
    void disconnectClearsCredential() {
        AccountService service = connected();
        Map<String, Object> status = service.disconnect();
        assertEquals(false, status.get("connected"));
        assertFalse(service.isConnected());
        assertFalse(Files.exists(tempDir.resolve("account.json")));
    }

    // ==================== 数据拉取 ====================

    @Test
    @DisplayName("ledger 按官网契约解 {entries:[...]}，不是裸数组")
    void ledgerUnwrapsEntries() {
        AccountService service = connected();
        transport.enqueue(200, "{\"entries\":[{\"kind\":\"recharge\",\"amountCents\":1000},"
                + "{\"kind\":\"ai_alloc\",\"amountCents\":-500}]}");
        List<Map<String, Object>> entries = service.fetchLedger();
        assertEquals(2, entries.size());
        assertEquals("ai_alloc", entries.get(1).get("kind"));
    }

    @Test
    @DisplayName("entitlements 解 {entitlements:[...]}，并刷新 lastSyncAt")
    void entitlementsUnwrapAndTouchSync() {
        AccountService service = connected();
        transport.enqueue(200, "{\"entitlements\":[{\"feature\":\"clipboard.unlimited\","
                + "\"purchasedAt\":\"2026-08-01T00:00:00Z\",\"orderId\":\"o1\"}]}");
        List<Map<String, Object>> list = service.fetchEntitlements();
        assertEquals(1, list.size());
        assertEquals("clipboard.unlimited", list.get(0).get("feature"));
        assertNotNull(service.status().get("lastSyncAt"));
    }

    @Test
    @DisplayName("ai-key 409 no_credits：引导去充值，且文案不得像掉线")
    void aiKeyNoCreditsGivesGuidance() {
        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"no_credits\"}");
        AccountException e = assertThrows(AccountException.class, service::fetchAiKey);
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertEquals("账户 Credits 余额为空，到官网充值后即可使用平台 AI", e.getMessage());
        for (String marker : new String[] {"登录", "未授权", "请先"}) {
            assertFalse(e.getMessage().contains(marker), "Credits 文案不得含「" + marker + "」");
        }
    }

    @Test
    @DisplayName("ai-key 409 no_allocation：旧版官网的分支仍能给出可读引导")
    void aiKeyLegacyNoAllocationStillHandled() {
        // 官网 Credits 重构后不再返回这个码，但桌面端可能连到尚未升级的官网
        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"no_allocation\"}");
        AccountException e = assertThrows(AccountException.class, service::fetchAiKey);
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertEquals("官网尚未为该账户签发 AI 通道密钥，到官网账户页看一下", e.getMessage());
    }

    @Test
    @DisplayName("账户类信封不许带 4010，否则前端会误清会话/跳登录页")
    void accountMessagesDoNotLookLikeAuthErrors() {
        // PR4-0 起 frontend/src/services/api.js 只认 code === 4010 判定未登录（清 session，
        // 浏览器端还会 reLaunch 登录页），不再做中文子串匹配。账户未连接、未分配额度都与
        // 登录无关：它们经两处 @ExceptionHandler(AccountException) 转信封时必须是 code=1。
        AccountService notConnected = service();
        AccountException notConnectedEx = assertThrows(AccountException.class, notConnected::fetchProfile);

        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"no_allocation\"}");
        AccountException noAllocationEx = assertThrows(AccountException.class, service::fetchAiKey);

        var accountController = new com.checkba.controller.AccountController(null, null, null, null, null, null);
        var keyController = new com.checkba.controller.PlatformAiKeyController(null, null);
        for (AccountException e : new AccountException[] {notConnectedEx, noAllocationEx}) {
            assertEquals(1, accountController.handleAccountException(e).getBody().get("code"),
                    "AccountController 信封不许带 4010：" + e.getMessage());
            assertEquals(1, keyController.handleAccountException(e).getBody().get("code"),
                    "PlatformAiKeyController 信封不许带 4010：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("ai-key 成功：返回 openrouterKey 与 limitUsd")
    void aiKeySuccess() {
        AccountService service = connected();
        transport.enqueue(200, "{\"openrouterKey\":\"sk-or-v1-provisioned\",\"limitUsd\":5.0}");
        Map<String, Object> body = service.fetchAiKey();
        assertEquals("sk-or-v1-provisioned", body.get("openrouterKey"));
        assertEquals(5.0, ((Number) body.get("limitUsd")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("ai-usage：返回额度三个数，hasKey 区分「未分配额度」")
    void aiUsageReturnsQuota() {
        AccountService service = connected();
        transport.enqueue(200, "{\"configured\":true,\"hasKey\":true,"
                + "\"limitUsd\":5.0,\"usageUsd\":1.25,\"remainingUsd\":3.75}");
        Map<String, Object> body = service.fetchAiUsage();
        assertEquals("GET https://www.aiworkdeck.com/api/account/ai-usage",
                transport.calls.get(transport.calls.size() - 1));
        assertEquals(Boolean.TRUE, body.get("hasKey"));
        assertEquals(3.75, ((Number) body.get("remainingUsd")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("ai-usage 端点不存在（旧官网）：抛 MALFORMED 由调用方降级，不拖垮整个用量面板")
    void aiUsageMissingEndpointIsRecoverable() {
        AccountService service = connected();
        transport.enqueue(404, "{}");
        AccountException e = assertThrows(AccountException.class, service::fetchAiUsage);
        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
    }

    @Test
    @DisplayName("官网返回非 JSON：分类为 MALFORMED，不当成鉴权失败")
    void malformedBodyIsClassified() {
        AccountService service = connected();
        transport.enqueue(200, "<html>502 bad gateway</html>");
        AccountException e = assertThrows(AccountException.class, service::fetchLedger);
        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
    }

    // ==================== 会员与充值（dev-board#183/#184） ====================

    @Test
    @DisplayName("membership：全量转发 GET /api/account/membership")
    void fetchMembershipForwards() {
        AccountService service = connected();
        transport.enqueue(200, "{\"growthPoints\":10,\"topupCents\":2000,\"spendCents\":500,"
                + "\"tier\":{\"key\":\"silver\",\"level\":2,\"nameZh\":\"白银\",\"nameEn\":\"Silver\",\"bonusPermille\":10},"
                + "\"nextTier\":null,\"tiers\":[]}");
        Map<String, Object> body = service.fetchMembership();
        assertEquals("GET https://www.aiworkdeck.com/api/account/membership",
                transport.calls.get(transport.calls.size() - 1));
        assertEquals(10, body.get("growthPoints"));
        assertNull(body.get("nextTier"));
    }

    @Test
    @DisplayName("membership：官网 401 只归 UNAUTHORIZED，不触碰 LicenseService——只有 connect() 才写解锁票据")
    void fetchMembershipUnauthorizedDoesNotTouchLicense() {
        com.checkba.service.LicenseService license =
                org.mockito.Mockito.mock(com.checkba.service.LicenseService.class);
        transport = new StubTransport()
                .enqueue(200, ME) // connect() 的 /api/account/me
                .enqueue(401, "{\"error\":\"unauthorized\"}"); // membership
        AccountService service = new AccountService(
                com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                tempDir.toString(), transport, license);
        service.connect(KEY);
        org.mockito.Mockito.reset(license); // connect() 已经合法地碰过一次，只关心 membership 之后

        AccountException e = assertThrows(AccountException.class, service::fetchMembership);
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        org.mockito.Mockito.verifyNoInteractions(license);
    }

    @Test
    @DisplayName("recharge：POST /api/payment/create 带 amount/kind/idempotencyKey，微信站 present:qrcode 原样透传")
    void createRechargeForwardsWechatShape() {
        AccountService service = connected();
        transport.enqueue(200, "{\"success\":true,\"present\":\"qrcode\",\"outTradeNo\":\"T1\","
                + "\"codeUrl\":\"weixin://wxpay/bizpayurl?pr=abc\",\"qrCode\":\"data:image/png;base64,xx\",\"amount\":1000}");
        Map<String, Object> body = service.createRecharge(1000, "idem-key-1");

        assertEquals("POST https://www.aiworkdeck.com/api/payment/create",
                transport.calls.get(transport.calls.size() - 1));
        String reqBody = transport.bodies.get(transport.bodies.size() - 1);
        assertTrue(reqBody.contains("\"amount\":1000"), reqBody);
        assertTrue(reqBody.contains("\"kind\":\"recharge\""), reqBody);
        assertTrue(reqBody.contains("idem-key-1"), reqBody);
        assertEquals(KEY, transport.lastBearer);
        assertEquals("qrcode", body.get("present"));
        assertEquals("T1", body.get("outTradeNo"));
    }

    @Test
    @DisplayName("recharge：Stripe 站 present:redirect 形状原样透传，桌面端不分叉")
    void createRechargeForwardsStripeShape() {
        AccountService service = connected();
        transport.enqueue(200, "{\"success\":true,\"present\":\"redirect\",\"outTradeNo\":\"T2\","
                + "\"amount\":1000,\"redirectUrl\":\"https://checkout.stripe.com/xyz\"}");
        Map<String, Object> body = service.createRecharge(1000, "idem-key-2");
        assertEquals("redirect", body.get("present"));
        assertEquals("https://checkout.stripe.com/xyz", body.get("redirectUrl"));
    }

    @Test
    @DisplayName("recharge：未连接账户直接 NOT_CONNECTED，一个请求都不发")
    void createRechargeNotConnectedShortCircuits() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class, () -> service().createRecharge(1000, "idem"));
        assertEquals(AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    @DisplayName("recharge/status：转发 GET /api/payment/query?outTradeNo=xxx，字段原样透传")
    void queryRechargeForwards() {
        AccountService service = connected();
        transport.enqueue(200, "{\"outTradeNo\":\"T1\",\"status\":\"paid\"}");
        Map<String, Object> body = service.queryRecharge("T1");
        assertEquals("GET https://www.aiworkdeck.com/api/payment/query?outTradeNo=T1",
                transport.calls.get(transport.calls.size() - 1));
        assertEquals("paid", body.get("status"));
    }

    @Test
    @DisplayName("recharge/status：查到 order.status=paid 时作废余额缓存，chip 刷新读到的必须是新余额")
    void queryRechargePaidInvalidatesBalanceCache() {
        AccountService service = connected();
        // 先把 profile 缓存喂热（balanceSnapshot 会拉 profile + membership）
        transport.enqueue(200, "{\"balanceCents\":100,\"plan\":\"paid\"}");
        transport.enqueue(200, "{\"tier\":null}");
        service.balanceSnapshot();
        // 查询到已支付
        transport.enqueue(200, "{\"success\":true,\"order\":{\"outTradeNo\":\"T1\",\"status\":\"paid\"}}");
        service.queryRecharge("T1");
        // 再取余额必须重新出站（缓存已作废），拿到充值后的新值
        transport.enqueue(200, "{\"balanceCents\":10100,\"plan\":\"paid\"}");
        transport.enqueue(200, "{\"tier\":null}");
        Map<String, Object> snapshot = service.balanceSnapshot();
        assertEquals(10100, snapshot.get("balanceCents"));
    }

    // ==================== SKU 购买（dev-board#187） ====================

    @Test
    @DisplayName("purchase-sku：POST /api/account/purchase 带 skuId，成功响应原样透传")
    void purchaseSkuForwards() {
        AccountService service = connected();
        transport.enqueue(200, "{\"ok\":true,\"feature\":\"clipboard.unlimited\","
                + "\"priceCents\":1990,\"balanceCents\":8010,\"orderId\":\"o-1\"}");
        Map<String, Object> body = service.purchaseSku("feature:clipboard.unlimited");
        assertEquals("POST https://www.aiworkdeck.com/api/account/purchase",
                transport.calls.get(transport.calls.size() - 1));
        assertTrue(transport.bodies.get(transport.bodies.size() - 1)
                .contains("\"skuId\":\"feature:clipboard.unlimited\""));
        assertEquals(KEY, transport.lastBearer);
        assertEquals("clipboard.unlimited", body.get("feature"));
        assertEquals(8010, body.get("balanceCents"));
    }

    @Test
    @DisplayName("purchase-sku：409 insufficient_credits 映射成带 reason 的业务异常，文案不像掉线")
    void purchaseSkuInsufficientCredits() {
        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"insufficient_credits\"}");
        SkuPurchaseException e = assertThrows(SkuPurchaseException.class,
                () -> service.purchaseSku("feature:stage.unlimited"));
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertEquals("insufficient_credits", e.getReason());
        assertFalse(e.getMessage().contains("登录"), e.getMessage());
        assertFalse(e.getMessage().contains("未授权"), e.getMessage());
        assertFalse(e.getMessage().contains("请先"), e.getMessage());
    }

    @Test
    @DisplayName("purchase-sku：409 already_owned 与 400 invalid_sku/not_purchasable 各有可区分的 reason")
    void purchaseSkuOtherFailures() {
        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"already_owned\"}");
        assertEquals("already_owned", assertThrows(SkuPurchaseException.class,
                () -> service.purchaseSku("feature:stage.unlimited")).getReason());
        transport.enqueue(400, "{\"error\":\"invalid_sku\"}");
        assertEquals("invalid_sku", assertThrows(SkuPurchaseException.class,
                () -> service.purchaseSku("feature:stage.unlimited")).getReason());
        transport.enqueue(400, "{\"error\":\"not_purchasable\"}");
        assertEquals("invalid_sku", assertThrows(SkuPurchaseException.class,
                () -> service.purchaseSku("feature:stage.unlimited")).getReason());
    }

    @Test
    @DisplayName("purchase-sku：未连接账户直接 NOT_CONNECTED，一个请求都不发")
    void purchaseSkuNotConnectedShortCircuits() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class,
                () -> service().purchaseSku("feature:stage.unlimited"));
        assertEquals(AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    @DisplayName("balance：未连接账户返回 {connected:false}，一个请求都不发")
    void balanceSnapshotNotConnected() {
        transport = new StubTransport();
        Map<String, Object> result = service().balanceSnapshot();
        assertEquals(false, result.get("connected"));
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    @DisplayName("balance：已连接时装配 profile + membership 摘要（只取四个展示字段）")
    void balanceSnapshotAssemblesProfileAndMembership() {
        AccountService service = connected();
        transport.enqueue(200, "{\"balanceCents\":500,\"plan\":\"free\"}");
        transport.enqueue(200, "{\"tier\":{\"key\":\"silver\",\"level\":2,\"nameZh\":\"白银\",\"nameEn\":\"Silver\",\"bonusPermille\":10}}");

        Map<String, Object> result = service.balanceSnapshot();

        assertEquals(true, result.get("connected"));
        assertEquals(500, result.get("balanceCents"));
        assertEquals("free", result.get("plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> membership = (Map<String, Object>) result.get("membership");
        assertNotNull(membership);
        assertEquals("silver", membership.get("key"));
        assertEquals(2, membership.get("level"));
        assertFalse(membership.containsKey("bonusPermille"), "balance 只挑四个展示字段，摘要不是全量转发");
    }

    @Test
    @DisplayName("balance：TTL 窗口内重复调用不再打官网——profile 60 秒、membership 10 分钟")
    void balanceSnapshotCachesWithinTtl() {
        AccountService service = connected();
        transport.enqueue(200, "{\"balanceCents\":500,\"plan\":\"free\"}");
        transport.enqueue(200, "{\"tier\":{\"key\":\"silver\"}}");
        service.balanceSnapshot();
        int callsAfterFirst = transport.calls.size();

        service.balanceSnapshot();
        service.balanceSnapshot();

        assertEquals(callsAfterFirst, transport.calls.size(), "TTL 窗口内不该再发官网请求");
    }

    @Test
    @DisplayName("balance：换账户后 clearBalanceCache 令缓存整体作废，下一次调用重新拉取新数据")
    void balanceSnapshotInvalidatedAfterClear() {
        AccountService service = connected();
        transport.enqueue(200, "{\"balanceCents\":500,\"plan\":\"free\"}");
        transport.enqueue(200, "{\"tier\":{\"key\":\"silver\"}}");
        service.balanceSnapshot();
        int callsBefore = transport.calls.size();

        service.clearBalanceCache();
        transport.enqueue(200, "{\"balanceCents\":900,\"plan\":\"paid\"}");
        transport.enqueue(200, "{\"tier\":{\"key\":\"gold\"}}");
        Map<String, Object> result = service.balanceSnapshot();

        assertTrue(transport.calls.size() > callsBefore, "清缓存之后必须重新发请求，不能继续吃旧值");
        assertEquals(900, result.get("balanceCents"),
                "换账户没清缓存的话，新账户会在 TTL 到期前一直看到上一个账户的余额");
    }

    @Test
    @DisplayName("balance：官网不可达时降级为 {connected:true, available:false}，同 usage 端点的口径")
    void balanceSnapshotDegradesWhenUnreachable() {
        AccountService service = connected();
        transport.enqueueNetworkFailure();
        Map<String, Object> result = service.balanceSnapshot();
        assertEquals(true, result.get("connected"));
        assertEquals(false, result.get("available"));
        assertNull(result.get("balanceCents"), "降级态不该带一个假的余额数字");
    }

    @Test
    @DisplayName("balance：membership 拉取失败单独降级为 null，不拖垮已经拿到的余额")
    void balanceSnapshotMembershipDegradesIndependently() {
        AccountService service = connected();
        transport.enqueue(200, "{\"balanceCents\":500,\"plan\":\"free\"}");
        transport.enqueueNetworkFailure(); // membership 这一路失败
        Map<String, Object> result = service.balanceSnapshot();
        assertEquals(true, result.get("connected"));
        assertEquals(500, result.get("balanceCents"), "余额段不该被 membership 的失败一起拖垮");
        assertTrue(result.containsKey("membership"), "字段形状必须稳定：即使拿不到也要有这个键");
        assertNull(result.get("membership"));
    }

    // ==================== 配置红线 ====================

    @Test
    @DisplayName("base-url 配成 http 直接拒绝：明文 Key 不允许走未加密通道")
    void httpBaseUrlRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AccountService(com.checkba.service.site.SiteProfileService.pinnedTo("http://www.aiworkdeck.com"), tempDir.toString(), new StubTransport(), null));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    @DisplayName("连上账户必须一并落解锁票据——不落的话「登录成功」但 launch 页会把人原地弹回解锁页")
    void connectAlsoMarksLicenseUnlocked() {
        java.util.concurrent.atomic.AtomicReference<String> marked = new java.util.concurrent.atomic.AtomicReference<>();
        com.checkba.service.LicenseService license =
                org.mockito.Mockito.mock(com.checkba.service.LicenseService.class);
        org.mockito.Mockito.doAnswer(inv -> { marked.set(inv.getArgument(0)); return null; })
                .when(license).markAccountUnlocked(org.mockito.ArgumentMatchers.anyString());

        transport = new StubTransport().enqueue(200, "{\"username\":\"u\"}");
        new AccountService(com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com"),
                tempDir.toString(), transport, license).connect(KEY);

        assertEquals(KEY, marked.get(),
                "解锁状态的唯一数据源是 license 票据；只写账户状态等于登录了却仍是未解锁");
    }

    @Test
    @DisplayName("回环 http 放行：本地起官网联调用，流量不出本机网卡")
    void loopbackHttpAllowed() {
        assertDoesNotThrow(() -> new AccountService(com.checkba.service.site.SiteProfileService.pinnedTo("http://localhost:3000"), tempDir.toString(), new StubTransport(), null));
        assertDoesNotThrow(() -> new AccountService(com.checkba.service.site.SiteProfileService.pinnedTo("http://127.0.0.1:3000"), tempDir.toString(), new StubTransport(), null));
    }

    @Test
    @DisplayName("尾部斜杠归一化：不能拼出 //api/account/me")
    void trailingSlashStripped() {
        transport = new StubTransport().enqueue(200, "{\"username\":\"u\"}");
        new AccountService(com.checkba.service.site.SiteProfileService.pinnedTo("https://www.aiworkdeck.com/"), tempDir.toString(), transport, null).connect(KEY);
        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(0));
    }
}
