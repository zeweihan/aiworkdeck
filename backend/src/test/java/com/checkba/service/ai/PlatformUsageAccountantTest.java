package com.checkba.service.ai;

import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 平台通道真实花费对账：差分口径（相邻两次累计消费之差）。
 * 这里算的是真花的钱，写错就是账不平——首次只建基线、采不到就不写、非正差分不写。
 *
 * server 模式多租户之后另加一条硬约束：<b>基线按密钥指纹分桶</b>，
 * 两个用户交替消费必须各算各的，否则就是把 A 的钱记在 B 头上。
 */
class PlatformUsageAccountantTest {

    private static final PlatformAiKeyService.Resolved KEY_A =
            new PlatformAiKeyService.Resolved("sk-or-v1-alice", "aaaaaaaaaaaa", 10.0);
    private static final PlatformAiKeyService.Resolved KEY_B =
            new PlatformAiKeyService.Resolved("sk-or-v1-bob", "bbbbbbbbbbbb", 10.0);

    private TokenUsageRepository repository;
    private PlatformAiChannel channel;
    /** 每把 key 各自的应答队列——多租户下两个用户的采样不能共用一条队列。 */
    private Map<String, Deque<AccountTransport.Reply>> replies;
    private PlatformUsageAccountant accountant;
    private TokenUsage row;
    private TokenUsage rowB;

    @BeforeEach
    void setUp() {
        repository = mock(TokenUsageRepository.class);
        channel = mock(PlatformAiChannel.class);
        when(channel.resolveFor(1L)).thenReturn(KEY_A);
        when(channel.resolveFor(2L)).thenReturn(KEY_B);

        row = new TokenUsage();
        row.setId(7L);
        row.setCostSource("platform");
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        rowB = new TokenUsage();
        rowB.setId(8L);
        rowB.setCostSource("platform");
        when(repository.findById(8L)).thenReturn(Optional.of(rowB));

        replies = new HashMap<>();
        AccountTransport transport = (method, url, bearer, body) -> {
            assertEquals("https://openrouter.ai/api/v1/key", url);
            Deque<AccountTransport.Reply> queue = replies.get(bearer);
            return queue == null || queue.isEmpty()
                    ? new AccountTransport.Reply(AccountTransport.Reply.NETWORK_FAILURE, null)
                    : queue.poll();
        };
        accountant = new PlatformUsageAccountant(repository, channel, transport,
                "https://openrouter.ai/api/v1");
        accountant.setPollIntervalMsForTest(1L);
    }

    private void usage(PlatformAiKeyService.Resolved key, double cumulative) {
        replies.computeIfAbsent(key.apiKey(), k -> new ArrayDeque<>())
                .add(new AccountTransport.Reply(200,
                        "{\"data\":{\"usage\":" + cumulative + ",\"limit\":10.0,\"limit_remaining\":9.0}}"));
    }

    private void usage(double cumulative) {
        usage(KEY_A, cumulative);
    }

    @Test
    @DisplayName("首次对账只建基线：之前的消费不属于这条记录")
    void firstCallOnlyEstablishesBaseline() {
        usage(1.25);
        accountant.reconcile(7L, 1L, KEY_A);
        verify(repository, never()).save(any());
        assertNull(row.getCost());
    }

    @Test
    @DisplayName("第二次对账写入差分作为真实花费")
    void deltaBecomesCost() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.250000"));
        usage(1.28);
        accountant.reconcile(7L, 1L, KEY_A);

        verify(repository).save(row);
        assertEquals(0, new BigDecimal("0.03").compareTo(row.getCost()), "cost=" + row.getCost());
        assertEquals("platform", row.getCostSource());
    }

    @Test
    @DisplayName("OpenRouter 记账有延迟：重采样直到数字变动")
    void retriesUntilUsageMoves() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        usage(1.00); // 还没记上
        usage(1.00);
        usage(1.02); // 记上了
        accountant.reconcile(7L, 1L, KEY_A);

        assertEquals(0, new BigDecimal("0.02").compareTo(row.getCost()));
    }

    @Test
    @DisplayName("采样一直不动：不写 cost（宁可留空也不写假数字）")
    void noMovementLeavesCostNull() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        for (int i = 0; i < 6; i++) usage(1.00);
        accountant.reconcile(7L, 1L, KEY_A);

        verify(repository, never()).save(any());
        assertNull(row.getCost());
    }

    @Test
    @DisplayName("采样失败（断网）：静默跳过，绝不影响对话")
    void probeFailureIsSilent() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        // replies 为空 → 桩返回 NETWORK_FAILURE
        assertDoesNotThrow(() -> accountant.reconcile(7L, 1L, KEY_A));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("拿不到平台 key：入队时就跳过，不发请求")
    void skipsWhenNoPlatformKey() {
        when(channel.resolveFor(9L)).thenReturn(null);
        assertDoesNotThrow(() -> accountant.reconcileAsync(7L, 9L));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("换账户必须重置基线：否则两把 key 的累计差会整个记到下一条消息头上")
    void resetBaselineDropsPreviousKeysCumulative() {
        // 账户 A 的累计是 0.02，账户 B 的累计是 5.00
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("0.02"));
        accountant.resetBaseline();

        usage(5.00);
        accountant.reconcile(7L, 1L, KEY_A);
        // 重置后第一次只重新建基线，不会把 4.98 写成这条消息的花费
        verify(repository, never()).save(any());
        assertNull(row.getCost());

        usage(5.01);
        accountant.reconcile(7L, 1L, KEY_A);
        assertEquals(0, new BigDecimal("0.01").compareTo(row.getCost()), "cost=" + row.getCost());
    }

    @Test
    @DisplayName("请求前建基线：随后第一条消息就能算出差分，不再永久「待结算」")
    void ensureBaselineMakesFirstMessageBillable() throws Exception {
        usage(2.00); // ensureBaseline 采到的
        accountant.ensureBaselineAsync(1L);
        // 分片 worker：等基线任务跑完
        for (int i = 0; i < 100 && accountant.baselineForTest(KEY_A.fingerprint()) == null; i++) {
            Thread.sleep(10);
        }

        usage(2.05); // 这条消息之后的累计
        accountant.reconcile(7L, 1L, KEY_A);
        assertEquals(0, new BigDecimal("0.05").compareTo(row.getCost()), "cost=" + row.getCost());
    }

    @Test
    @DisplayName("多租户核心回归：两个用户交替消费，各自差分独立，绝不串位")
    void twoUsersAreAccountedSeparately() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        accountant.setBaselineForTest(KEY_B.fingerprint(), new BigDecimal("50.00"));

        // A 花了 0.03，B 花了 2.00；若共用一个 baseline，B 这条会被记成 49 美元
        usage(KEY_A, 1.03);
        usage(KEY_B, 52.00);
        accountant.reconcile(7L, 1L, KEY_A);
        accountant.reconcile(8L, 2L, KEY_B);

        assertEquals(0, new BigDecimal("0.03").compareTo(row.getCost()), "A cost=" + row.getCost());
        assertEquals(0, new BigDecimal("2.00").compareTo(rowB.getCost()), "B cost=" + rowB.getCost());
    }

    @Test
    @DisplayName("OpenRouter 401：密钥已在官网侧吊销，立刻作废本地记录并清基线")
    void rejectedKeyIsEvictedImmediately() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        replies.computeIfAbsent(KEY_A.apiKey(), k -> new ArrayDeque<>())
                .add(new AccountTransport.Reply(401, "{\"error\":\"unauthorized\"}"));

        accountant.reconcile(7L, 1L, KEY_A);

        verify(channel).onKeyRejected(1L);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("网络不可达不等于凭据失效：绝不作废密钥")
    void networkFailureNeverEvicts() {
        accountant.setBaselineForTest(KEY_A.fingerprint(), new BigDecimal("1.00"));
        accountant.reconcile(7L, 1L, KEY_A); // 队列为空 → NETWORK_FAILURE

        verify(channel, never()).onKeyRejected(any());
    }

    @Test
    @DisplayName("采样成功即刷新验证时间：30 天宽限的计时起点")
    void successfulProbeMarksVerified() {
        usage(1.00);
        accountant.reconcile(7L, 1L, KEY_A);
        verify(channel).onKeyVerified(1L);
    }
}
