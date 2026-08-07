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
}
