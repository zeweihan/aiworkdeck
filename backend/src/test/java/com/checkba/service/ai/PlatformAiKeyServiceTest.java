package com.checkba.service.ai;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.PlatformAiKey;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.PlatformAiKeyRepository;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * per-user 平台 AI 密钥的取用与存储（server 模式多租户）。
 *
 * 守的几条：桥接取 key 失败绝不拖垮桥接；awdk_ 明文不落库；刷新必须校验 accountId 归属；
 * 30 天未验证即过期；官网明确拒绝立刻清除；失败文案不被前端误判为掉线。
 */
class PlatformAiKeyServiceTest {

    private static final String AWDK = "awdk_" + "KeyMaterial0123456789abcdefghijklmnop";
    private static final String RUNTIME_KEY = "sk-or-v1-provisioned-alice";

    private PlatformAiKeyRepository keyRepository;
    private AccountBindingRepository bindingRepository;
    private AccountService accountService;
    private PlatformAiKeyService service;

    private Map<Long, PlatformAiKey> rows;
    private Map<Long, AccountBinding> bindings;

    @BeforeEach
    void setUp() {
        rows = new HashMap<>();
        bindings = new HashMap<>();
        AtomicLong seq = new AtomicLong(1);

        keyRepository = mock(PlatformAiKeyRepository.class);
        when(keyRepository.findByUserId(any()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0, Long.class))));
        when(keyRepository.save(any(PlatformAiKey.class))).thenAnswer(inv -> {
            PlatformAiKey e = inv.getArgument(0);
            if (e.getId() == null) e.setId(seq.getAndIncrement());
            rows.put(e.getUserId(), e);
            return e;
        });
        doAnswer(inv -> rows.remove(inv.getArgument(0, PlatformAiKey.class).getUserId()))
                .when(keyRepository).delete(any(PlatformAiKey.class));
        when(keyRepository.count()).thenAnswer(inv -> (long) rows.size());

        bindingRepository = mock(AccountBindingRepository.class);
        when(bindingRepository.findByUserId(any()))
                .thenAnswer(inv -> Optional.ofNullable(bindings.get(inv.getArgument(0, Long.class))));
        when(bindingRepository.count()).thenAnswer(inv -> (long) bindings.size());

        accountService = mock(AccountService.class);
        service = new PlatformAiKeyService(keyRepository, bindingRepository,
                new PlatformAiKeyCipher("unit-test-secret", false), accountService);
    }

    private void bind(long userId, String accountId) {
        AccountBinding b = new AccountBinding();
        b.setUserId(userId);
        b.setExternalAccountId(accountId);
        bindings.put(userId, b);
    }

    private void websiteReturnsKey(String key, Double limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("openrouterKey", key);
        body.put("limitUsd", limit);
        when(accountService.fetchAiKeyWith(anyString())).thenReturn(body);
    }

    private static void assertNotMistakenForLogout(String message) {
        assertNotNull(message);
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(message.contains(forbidden), "文案不得含「" + forbidden + "」: " + message);
        }
    }

    // ==================== 取用 ====================

    @Test
    @DisplayName("取到即加密入库；解出来是同一把 key，且明文不出现在存储里")
    void provisionStoresEncrypted() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);

        PlatformAiKey stored = rows.get(1L);
        assertNotNull(stored);
        assertFalse(stored.getKeyEnc().contains(RUNTIME_KEY), "库里不得出现 runtime key 明文");
        assertFalse(stored.getKeyEnc().contains(AWDK), "库里更不得出现 awdk_ 明文");
        assertEquals(RUNTIME_KEY, service.resolve(1L).orElseThrow().apiKey());
        assertEquals(10.0, stored.getLimitUsd());
    }

    @Test
    @DisplayName("重复取：覆盖同一行（每用户至多一把）")
    void provisionIsIdempotentPerUser() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);
        websiteReturnsKey("sk-or-v1-rotated", 20.0);
        service.provision(1L, AWDK);

        assertEquals(1, rows.size());
        assertEquals("sk-or-v1-rotated", service.resolve(1L).orElseThrow().apiKey());
        assertEquals(20.0, rows.get(1L).getLimitUsd());
    }

    @Test
    @DisplayName("两个用户各一把：指纹不同，互不可见")
    void keysAreIsolatedPerUser() {
        websiteReturnsKey("sk-or-alice", 10.0);
        service.provision(1L, AWDK);
        websiteReturnsKey("sk-or-bob", 10.0);
        service.provision(2L, AWDK);

        assertEquals("sk-or-alice", service.resolve(1L).orElseThrow().apiKey());
        assertEquals("sk-or-bob", service.resolve(2L).orElseThrow().apiKey());
        assertNotEquals(service.fingerprintOrNull(1L), service.fingerprintOrNull(2L));
    }

    @Test
    @DisplayName("桥接取 key 失败（最常见是 409 还没分配额度）一律吞掉：不得拖垮桥接登录")
    void tryProvisionSwallowsFailures() {
        // doThrow 而不是 when().thenThrow()：已被 stub 成抛异常的 mock，在 when() 里再调一次会当场抛
        doThrow(new AccountException(AccountException.Kind.CONFLICT,
                "尚未分配 AI 额度，请到官网账户页从余额分配"))
                .when(accountService).fetchAiKeyWith(anyString());
        assertDoesNotThrow(() -> service.tryProvision(1L, AWDK));
        assertTrue(rows.isEmpty());

        doThrow(new AccountException(AccountException.Kind.NETWORK,
                "无法连接 AI Workdeck 服务器，请检查网络后重试"))
                .when(accountService).fetchAiKeyWith(anyString());
        assertDoesNotThrow(() -> service.tryProvision(1L, AWDK));

        doThrow(new RuntimeException("boom")).when(accountService).fetchAiKeyWith(anyString());
        assertDoesNotThrow(() -> service.tryProvision(1L, AWDK));
        assertTrue(rows.isEmpty(), "取不到就什么都不写");
    }

    @Test
    @DisplayName("未配置存储密钥：明确的业务错误，不明文降级")
    void provisionWithoutSecretIsRefused() {
        PlatformAiKeyService noSecret = new PlatformAiKeyService(keyRepository, bindingRepository,
                new PlatformAiKeyCipher("", false), accountService);
        AccountException e = assertThrows(AccountException.class, () -> noSecret.provision(1L, AWDK));
        assertNotMistakenForLogout(e.getMessage());
        assertTrue(rows.isEmpty());
    }

    // ==================== 过期与吊销 ====================

    @Test
    @DisplayName("29 天前验证过：仍可用；31 天：判为过期（永久离线不等于永久可用）")
    void offlineGraceIsThirtyDays() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);

        rows.get(1L).setLastVerifiedAt(LocalDateTime.now().minusDays(29));
        assertTrue(service.resolve(1L).isPresent());
        assertNotNull(service.fingerprintOrNull(1L));

        rows.get(1L).setLastVerifiedAt(LocalDateTime.now().minusDays(31));
        assertTrue(service.resolve(1L).isEmpty(), "超 30 天未验证必须判过期");
        assertNull(service.fingerprintOrNull(1L));
    }

    @Test
    @DisplayName("探针成功刷新验证时间，过期的密钥随之复活")
    void markVerifiedRefreshesGrace() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);
        rows.get(1L).setLastVerifiedAt(LocalDateTime.now().minusDays(31));
        assertTrue(service.resolve(1L).isEmpty());

        service.markVerified(1L);
        assertTrue(service.resolve(1L).isPresent());
    }

    @Test
    @DisplayName("官网明确拒绝：立刻删行，不吃宽限")
    void evictDeletesRow() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);
        service.evict(1L);

        assertTrue(rows.isEmpty());
        assertTrue(service.resolve(1L).isEmpty());
    }

    @Test
    @DisplayName("secret 被换过导致解不开：按不可用降级，但绝不删行（改回 secret 即恢复）")
    void undecryptableRowIsNotDeleted() {
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);

        PlatformAiKeyService rotated = new PlatformAiKeyService(keyRepository, bindingRepository,
                new PlatformAiKeyCipher("another-secret", false), accountService);
        assertTrue(rotated.resolve(1L).isEmpty());
        assertFalse(rows.isEmpty(), "解不开不等于该丢——这类故障是可修复的");
    }

    // ==================== 刷新 ====================

    @Test
    @DisplayName("刷新：Key 归属本账号才放行")
    void refreshRequiresMatchingAccount() {
        bind(1L, "acc_alice");
        when(accountService.fetchProfileWith(anyString()))
                .thenReturn(Map.of("accountId", "acc_alice", "username", "alice"));
        websiteReturnsKey(RUNTIME_KEY, 10.0);

        service.refresh(1L, AWDK);
        assertEquals(RUNTIME_KEY, service.resolve(1L).orElseThrow().apiKey());
    }

    @Test
    @DisplayName("刷新：贴别人的 Key 必拒（否则等于把别人的额度装到自己名下用）")
    void refreshRejectsForeignKey() {
        bind(1L, "acc_alice");
        when(accountService.fetchProfileWith(anyString()))
                .thenReturn(Map.of("accountId", "acc_bob", "username", "bob"));
        websiteReturnsKey(RUNTIME_KEY, 10.0);

        AccountException e = assertThrows(AccountException.class, () -> service.refresh(1L, AWDK));
        assertNotMistakenForLogout(e.getMessage());
        assertTrue(rows.isEmpty(), "校验没过就不该有任何写入");
        verify(accountService, never()).fetchAiKeyWith(anyString());
    }

    @Test
    @DisplayName("刷新：官网响应缺 accountId 一律拒绝，不回落 username 做归属判据")
    void refreshRejectsMissingAccountId() {
        bind(1L, "acc_alice");
        when(accountService.fetchProfileWith(anyString())).thenReturn(Map.of("username", "alice"));

        AccountException e = assertThrows(AccountException.class, () -> service.refresh(1L, AWDK));
        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
        assertNotMistakenForLogout(e.getMessage());
    }

    @Test
    @DisplayName("刷新：没桥接过的账号明确拒绝，且不向官网发请求")
    void refreshRequiresBinding() {
        AccountException e = assertThrows(AccountException.class, () -> service.refresh(1L, AWDK));
        assertNotMistakenForLogout(e.getMessage());
        verify(accountService, never()).fetchProfileWith(anyString());
    }

    @Test
    @DisplayName("刷新：Key 格式不对当场拒绝，不浪费一次官网往返")
    void refreshValidatesKeyShape() {
        bind(1L, "acc_alice");
        AccountException e = assertThrows(AccountException.class, () -> service.refresh(1L, "not-a-key"));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        verify(accountService, never()).fetchProfileWith(anyString());
    }

    // ==================== 判据与展示 ====================

    @Test
    @DisplayName("绑定判据：有映射才算已桥接；任一映射存在即为多租户形态")
    void bindingPredicates() {
        assertFalse(service.isBound(1L));
        assertFalse(service.multiTenant());

        bind(1L, "acc_alice");
        assertTrue(service.isBound(1L));
        assertTrue(service.multiTenant());
        assertFalse(service.isBound(2L));
        assertFalse(service.isBound(null));
    }

    @Test
    @DisplayName("额度面板：密钥明文不出后端，用量拿不到时不把 0 当成剩余额度")
    void statusNeverLeaksKeyAndDegradesUsage() {
        bind(1L, "acc_alice");
        websiteReturnsKey(RUNTIME_KEY, 10.0);
        service.provision(1L, AWDK);

        PlatformUsageAccountant accountant = mock(PlatformUsageAccountant.class);
        when(accountant.probeUsageForDisplay(any(), anyString())).thenReturn(null);

        Map<String, Object> status = service.status(1L, accountant);
        assertEquals(Boolean.TRUE, status.get("available"));
        assertEquals(10.0, status.get("limitUsd"));
        assertNull(status.get("usageUsd"));
        assertNull(status.get("remainingUsd"), "用量未知时剩余必须是 null，不能顶成 0");
        assertEquals(Boolean.FALSE, status.get("usageAvailable"));
        assertFalse(String.valueOf(status).contains(RUNTIME_KEY), "状态里绝不能出现密钥明文");

        when(accountant.probeUsageForDisplay(any(), anyString())).thenReturn(2.5);
        Map<String, Object> withUsage = service.status(1L, accountant);
        assertEquals(2.5, withUsage.get("usageUsd"));
        assertEquals(7.5, withUsage.get("remainingUsd"));
    }
}
