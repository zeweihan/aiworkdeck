package com.checkba.service.account;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.DeviceToken;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 awdk_ → server 会话桥的契约（插件云后端，官网侧尚未实施 accountId，全程 mock 官网）：
 * - 开关关闭：业务错误，出站请求根本不发；
 * - 有效 Key 首登：建无密码用户 + account_binding 映射，签发 awdt_ 且能解析回该用户；
 * - 同 accountId 再登：复用映射，不再建号；
 * - 无效 Key（401）：UNAUTHORIZED 拒绝；
 * - 官网响应缺 accountId：MALFORMED 拒绝（不回落 username 做映射键）;
 * - awdk_ 明文不落库；
 * - 所有失败文案不含「登录」「未授权」「请先」子串。
 *
 * 账户登录（手机号/邮箱直登）那一组在文件末尾：换 Key → 复用同一条桥，
 * 两个入口落到同一个 server 用户，以及开关关闭时一条出站都不发。
 */
class AwdkLoginServiceTest {

    private static final String KEY = "awdk_" + "BridgeKeyMaterial0123456789abcdefghijkl";
    private static final String ME_OK =
            "{\"accountId\":\"acc_9f3a\",\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\","
                    + "\"balanceCents\":1980,\"plan\":\"paid\"}";

    /** 可编排的出站桩（与 AccountServiceTest 同款）。 */
    static class StubTransport implements AccountTransport {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
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
            bodies.add(jsonBody);
            bearers.add(bearerKey);
            lastBearer = bearerKey;
            if (replies.isEmpty()) {
                throw new AssertionError("桩没有为 " + method + " " + url + " 准备响应");
            }
            return replies.poll();
        }
    }

    private StubTransport transport;
    private UserService userService;
    private DeviceTokenService deviceTokenService;
    private AccountBindingRepository bindingRepository;
    private com.checkba.service.ai.PlatformAiKeyService platformAiKeyService;

    // 内存假库
    private final Map<String, User> usersByName = new HashMap<>();
    private final Map<Long, User> usersById = new HashMap<>();
    private final Map<String, AccountBinding> bindingsByAccountId = new HashMap<>();
    private final Map<String, DeviceToken> tokensByHash = new HashMap<>();
    private final AtomicLong userSeq = new AtomicLong(1);
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        usersByName.clear();
        usersById.clear();
        bindingsByAccountId.clear();
        tokensByHash.clear();

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(usersByName.get(inv.getArgument(0, String.class))));
        when(userRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(usersById.get(inv.getArgument(0, Long.class))));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(userSeq.getAndIncrement());
            usersByName.put(u.getUsername(), u);
            usersById.put(u.getId(), u);
            return u;
        });
        userService = new UserService(userRepository);

        DeviceTokenRepository tokenRepository = mock(DeviceTokenRepository.class);
        when(tokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            tokensByHash.put(t.getTokenHash(), t);
            return t;
        });
        when(tokenRepository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tokensByHash.get(inv.getArgument(0, String.class))));
        deviceTokenService = new DeviceTokenService(tokenRepository);

        platformAiKeyService = mock(com.checkba.service.ai.PlatformAiKeyService.class);

        bindingRepository = mock(AccountBindingRepository.class);
        when(bindingRepository.findByExternalAccountId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(bindingsByAccountId.get(inv.getArgument(0, String.class))));
        when(bindingRepository.save(any(AccountBinding.class))).thenAnswer(inv -> {
            AccountBinding b = inv.getArgument(0);
            if (b.getId() == null) b.setId(seq.getAndIncrement());
            bindingsByAccountId.put(b.getExternalAccountId(), b);
            return b;
        });
    }

    private AwdkLoginService service(boolean enabled) {
        return new AwdkLoginService(enabled, "https://www.aiworkdeck.com",
                transport, bindingRepository, userService, deviceTokenService, platformAiKeyService);
    }

    private static void assertNotMistakenForLogout(String message) {
        assertNotNull(message);
        assertFalse(message.contains("登录"), "文案不得含「登录」: " + message);
        assertFalse(message.contains("未授权"), "文案不得含「未授权」: " + message);
        assertFalse(message.contains("请先"), "文案不得含「请先」: " + message);
    }

    @Test
    @DisplayName("开关关闭（默认）：业务错误，出站请求根本不发")
    void disabledRejectsWithoutOutboundCall() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(false).login(KEY));
        assertTrue(e.getMessage().contains("未开启账户桥接"), e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
        assertTrue(transport.calls.isEmpty(), "开关关闭时不得向官网发请求");
    }

    @Test
    @DisplayName("有效 Key 首登：建无密码用户 + 绑定映射，awdt_ 令牌解析回该用户")
    void firstLoginCreatesBindingAndPasswordlessUser() {
        transport.enqueue(200, ME_OK);

        AwdkLoginService.BridgeSession session = service(true).login(KEY);

        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(0));
        assertEquals(KEY, transport.lastBearer, "校验请求必须带 Bearer <key>");

        // 令牌形态与既有设备令牌一致，且解析回新建用户
        assertTrue(session.token().startsWith(DeviceTokenService.TOKEN_PREFIX));
        assertEquals(session.userId(), deviceTokenService.resolveUserId(session.token()));

        // 用户：awd_ 前缀、无密码（哨兵），密码登录被拒
        assertEquals("awd_hanzewei", session.username());
        User created = usersByName.get("awd_hanzewei");
        assertTrue(created.getPassword().startsWith(UserService.EXTERNAL_ACCOUNT_MARK));
        assertThrows(IllegalArgumentException.class,
                () -> userService.login("awd_hanzewei", created.getPassword()));

        // 绑定：键是 accountId；awdk_ 明文不落库
        AccountBinding binding = bindingsByAccountId.get("acc_9f3a");
        assertNotNull(binding);
        assertEquals(session.userId(), binding.getUserId());
        assertFalse(binding.toString().contains(KEY));
    }

    @Test
    @DisplayName("同 accountId 再登：复用映射同一用户，不再建号")
    void repeatLoginReusesBinding() {
        transport.enqueue(200, ME_OK).enqueue(200, ME_OK);
        AwdkLoginService svc = service(true);

        AwdkLoginService.BridgeSession first = svc.login(KEY);
        AwdkLoginService.BridgeSession second = svc.login(KEY);

        assertEquals(first.userId(), second.userId());
        assertEquals(1, usersByName.size(), "不得重复建号");
        assertNotEquals(first.token(), second.token(), "每次桥接签发独立令牌");
    }

    @Test
    @DisplayName("官网用户名与本服务器既有账号撞名：新建带哈希后缀的用户，绝不绑到既有账号")
    void usernameCollisionNeverHijacksExistingAccount() {
        // 本服务器已有一个人工注册的 awd_hanzewei
        User incumbent = userService.register("awd_hanzewei", "pw123456", "本地同名用户");
        transport.enqueue(200, ME_OK);

        AwdkLoginService.BridgeSession session = service(true).login(KEY);

        assertNotEquals(incumbent.getId(), session.userId(), "撞名绝不能接管既有账号");
        assertTrue(session.username().startsWith("awd_hanzewei_"), session.username());
    }

    @Test
    @DisplayName("无效 Key（401）：UNAUTHORIZED 拒绝，不建任何数据")
    void invalidKeyRejected() {
        transport.enqueue(401, "{\"error\":\"invalid_key\"}");

        AccountException e = assertThrows(AccountException.class, () -> service(true).login(KEY));

        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertNotMistakenForLogout(e.getMessage());
        assertFalse(e.getMessage().contains(KEY), "文案不得含 Key 明文");
        assertTrue(usersByName.isEmpty());
        assertTrue(bindingsByAccountId.isEmpty());
    }

    @Test
    @DisplayName("Key 前缀不对：不发出站请求直接拒绝")
    void malformedKeyRejectedWithoutOutboundCall() {
        AccountException e = assertThrows(AccountException.class,
                () -> service(true).login("sk-or-v1-not-awdk"));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    @DisplayName("官网响应缺 accountId（官网侧尚未实施）：MALFORMED 拒绝，不回落 username 做映射键")
    void missingAccountIdRejected() {
        transport.enqueue(200, "{\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\"}");

        AccountException e = assertThrows(AccountException.class, () -> service(true).login(KEY));

        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
        assertTrue(e.getMessage().contains("accountId"), e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
        assertTrue(usersByName.isEmpty(), "缺映射键时绝不能按 username 建号");
    }

    @Test
    @DisplayName("网络不可达：NETWORK（调用方不计入失败锁定）")
    void networkFailureIsNetworkKind() {
        transport.enqueueNetworkFailure();
        AccountException e = assertThrows(AccountException.class, () -> service(true).login(KEY));
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertNotMistakenForLogout(e.getMessage());
    }

    @Test
    @DisplayName("绑定指向的用户已被删除：按首登重建并改指新用户")
    void deletedUserGetsRecreated() {
        transport.enqueue(200, ME_OK).enqueue(200, ME_OK);
        AwdkLoginService svc = service(true);
        AwdkLoginService.BridgeSession first = svc.login(KEY);

        // 管理员删掉了该用户
        User gone = usersById.remove(first.userId());
        usersByName.remove(gone.getUsername());

        AwdkLoginService.BridgeSession second = svc.login(KEY);
        assertNotEquals(first.userId(), second.userId());
        assertEquals(second.userId(), bindingsByAccountId.get("acc_9f3a").getUserId());
    }

    // ==================== per-user 平台 AI key（2026-08-07） ====================

    @Test
    @DisplayName("桥接成功即为该用户换一把平台 AI 密钥：awdk_ 只在这一刻被用到")
    void bridgeProvisionsPerUserPlatformKey() {
        transport.enqueue(200, ME_OK);
        AwdkLoginService.BridgeSession session = service(true).login(KEY);

        // 取 key 走的是「短暂持有的原始 awdk_」这一条路（不落库的前提下唯一可行的时机）
        org.mockito.Mockito.verify(platformAiKeyService).tryProvision(session.userId(), KEY);
    }

    @Test
    @DisplayName("Key 无效时不得去取平台密钥：官网都没认，更不该拿它换 runtime key")
    void invalidKeyNeverProvisions() {
        transport.enqueue(401, "{\"error\":\"unauthorized\"}");
        assertThrows(AccountException.class, () -> service(true).login(KEY));

        org.mockito.Mockito.verify(platformAiKeyService, org.mockito.Mockito.never())
                .tryProvision(anyLong(), anyString());
    }

    // ==================== 账户登录（手机号/邮箱直登，用户看不见 Key） ====================

    @Test
    @DisplayName("手机号登录：换 Key 后走同一条桥，用户全程看不到 Key")
    void phoneLoginExchangesKeyThenBridges() {
        transport.enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":true}").enqueue(200, ME_OK);

        AwdkLoginService.BridgeSession session = service(true).loginWithPhone("13800138000", "123456");

        assertEquals("POST https://www.aiworkdeck.com/api/auth/exchange-key", transport.calls.get(0));
        assertNull(transport.bearers.get(0), "登录阶段还没有 Key，不该带 Authorization");
        assertTrue(transport.bodies.get(0).contains("13800138000"));
        // 换到 Key 之后必须复用既有桥接：me 拿 accountId 才有映射键
        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(1));
        assertEquals(KEY, transport.bearers.get(1));

        assertTrue(session.token().startsWith(DeviceTokenService.TOKEN_PREFIX));
        assertEquals(session.userId(), deviceTokenService.resolveUserId(session.token()));
        assertEquals("awd_hanzewei", session.username());
        assertNotNull(bindingsByAccountId.get("acc_9f3a"));
    }

    @Test
    @DisplayName("换 Key 请求带上设备名，固定为「Office 插件」（默认站点 aiworkdeck.com）")
    void exchangeKeyRequestIncludesFixedDeviceName() {
        transport.enqueue(200, "{\"key\":\"" + KEY + "\",\"isNewUser\":true}").enqueue(200, ME_OK);

        service(true).loginWithPhone("13800138000", "123456");

        assertTrue(transport.bodies.get(0).contains("\"deviceName\":\"Office 插件\""), transport.bodies.get(0));
    }

    @Test
    @DisplayName("口令登录：国际站主路径，凭据形状不同但桥接结果一样")
    void passwordLoginExchangesKeyThenBridges() {
        transport.enqueue(200, "{\"key\":\"" + KEY + "\"}").enqueue(200, ME_OK);

        AwdkLoginService.BridgeSession session = service(true).loginWithPassword("hi@example.com", "pw12345678");

        assertEquals("POST https://www.aiworkdeck.com/api/auth/exchange-key", transport.calls.get(0));
        assertTrue(transport.bodies.get(0).contains("hi@example.com"));
        assertFalse(transport.bodies.get(0).contains("13800138000"));
        assertEquals(session.userId(), deviceTokenService.resolveUserId(session.token()));
    }

    @Test
    @DisplayName("同一个官网账户，两条入口（手机号登录 / 手工粘 Key）落到同一个 server 用户")
    void phoneLoginAndPastedKeyShareOneUser() {
        transport.enqueue(200, "{\"key\":\"" + KEY + "\"}").enqueue(200, ME_OK).enqueue(200, ME_OK);
        AwdkLoginService svc = service(true);

        AwdkLoginService.BridgeSession viaPhone = svc.loginWithPhone("13800138000", "123456");
        AwdkLoginService.BridgeSession viaKey = svc.login(KEY);

        assertEquals(viaPhone.userId(), viaKey.userId());
        assertEquals(1, usersByName.size(), "同一个 accountId 不得建出两个用户");
    }

    @Test
    @DisplayName("验证码错误：UNAUTHORIZED 且透传官网文案（不能套用「Key 无效」那句）")
    void wrongCodeSurfacesWebsiteMessage() {
        transport.enqueue(401, "{\"error\":\"invalid_code\",\"message\":\"验证码错误或已过期\"}");

        AccountException e = assertThrows(AccountException.class,
                () -> service(true).loginWithPhone("13800138000", "000000"));

        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertEquals("验证码错误或已过期", e.getMessage());
        assertTrue(usersByName.isEmpty());
        assertTrue(bindingsByAccountId.isEmpty());
    }

    @Test
    @DisplayName("补绑期已过（phone_binding_required）：CONFLICT，调用方据此不计入失败锁定")
    void bindingDeadlinePassedIsConflictNotCredentialFailure() {
        transport.enqueue(403, "{\"error\":\"phone_binding_required\"}");

        AccountException e = assertThrows(AccountException.class,
                () -> service(true).loginWithPassword("hi@example.com", "pw12345678"));

        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertTrue(e.getMessage().contains("hi@aiworkdeck.com"), e.getMessage());
    }

    @Test
    @DisplayName("官网没返回 key：MALFORMED，且文案不提「粘贴 Key」（这条路的用户没见过 Key）")
    void missingKeyInExchangeReplyIsMalformed() {
        transport.enqueue(200, "{\"isNewUser\":true}");

        AccountException e = assertThrows(AccountException.class,
                () -> service(true).loginWithPhone("13800138000", "123456"));

        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
        assertFalse(e.getMessage().contains("awdk_"), e.getMessage());
        assertEquals(1, transport.calls.size(), "拿不到 Key 就不该再去调 me");
    }

    @Test
    @DisplayName("开关关闭：账户登录与发验证码都不出站（关着的桥不该变成短信/口令转发口）")
    void disabledBlocksAccountLoginAndCodeSend() {
        AwdkLoginService svc = service(false);

        assertThrows(IllegalArgumentException.class, () -> svc.sendLoginCode("13800138000", null));
        assertThrows(IllegalArgumentException.class, () -> svc.loginWithPhone("13800138000", "123456"));
        assertThrows(IllegalArgumentException.class, () -> svc.loginWithPassword("a@b.com", "pw12345678"));

        assertTrue(transport.calls.isEmpty(), "开关关闭时一条出站都不许发");
    }

    @Test
    @DisplayName("发验证码：转发官网 sms-login/send-code，不带 Authorization")
    void sendLoginCodeForwardsToWebsite() {
        transport.enqueue(200, "{\"sent\":true}");

        service(true).sendLoginCode(" 13800138000 ", "tok-abc");

        assertEquals("POST https://www.aiworkdeck.com/api/auth/sms-login/send-code", transport.calls.get(0));
        assertNull(transport.bearers.get(0));
        assertTrue(transport.bodies.get(0).contains("13800138000"));
        assertFalse(transport.bodies.get(0).contains(" 13800138000 "), "手机号应已 trim");
        // 这是到官网 send-code 的第二条转发链（另一条在 AccountService）。官网启用人机验证后
        // 漏掉 token 这条链就整条断，而且只有插件用户会踩到——那是最难被发现的一类回归。
        assertTrue(transport.bodies.get(0).contains("tok-abc"), "人机验证 token 必须原样透传");
    }

    @Test
    @DisplayName("发验证码时官网不可达：NETWORK（调用方据此不计入失败锁定）")
    void sendLoginCodeNetworkFailure() {
        transport.enqueueNetworkFailure();
        AccountException e = assertThrows(AccountException.class,
                () -> service(true).sendLoginCode("13800138000", null));
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
    }
}
