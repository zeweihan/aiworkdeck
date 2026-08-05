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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 平台通道真实花费对账：差分口径（相邻两次累计消费之差）。
 * 这里算的是真花的钱，写错就是账不平——首次只建基线、采不到就不写、非正差分不写。
 */
class PlatformUsageAccountantTest {

    private TokenUsageRepository repository;
    private PlatformAiChannel channel;
    private Deque<AccountTransport.Reply> replies;
    private PlatformUsageAccountant accountant;
    private TokenUsage row;

    @BeforeEach
    void setUp() {
        repository = mock(TokenUsageRepository.class);
        channel = mock(PlatformAiChannel.class);
        when(channel.apiKey()).thenReturn("sk-or-v1-provisioned");

        row = new TokenUsage();
        row.setId(7L);
        row.setCostSource("platform");
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        replies = new ArrayDeque<>();
        AccountTransport transport = (method, url, bearer, body) -> {
            assertEquals("https://openrouter.ai/api/v1/key", url);
            assertEquals("sk-or-v1-provisioned", bearer);
            return replies.isEmpty()
                    ? new AccountTransport.Reply(AccountTransport.Reply.NETWORK_FAILURE, null)
                    : replies.poll();
        };
        accountant = new PlatformUsageAccountant(repository, channel, transport,
                "https://openrouter.ai/api/v1");
        accountant.setPollIntervalMsForTest(1L);
    }

    private void usage(double cumulative) {
        replies.add(new AccountTransport.Reply(200,
                "{\"data\":{\"usage\":" + cumulative + ",\"limit\":10.0,\"limit_remaining\":9.0}}"));
    }

    @Test
    @DisplayName("首次对账只建基线：之前的消费不属于这条记录")
    void firstCallOnlyEstablishesBaseline() {
        usage(1.25);
        accountant.reconcile(7L);
        verify(repository, never()).save(any());
        assertNull(row.getCost());
    }

    @Test
    @DisplayName("第二次对账写入差分作为真实花费")
    void deltaBecomesCost() {
        accountant.setBaselineForTest(new BigDecimal("1.250000"));
        usage(1.28);
        accountant.reconcile(7L);

        verify(repository).save(row);
        assertEquals(0, new BigDecimal("0.03").compareTo(row.getCost()), "cost=" + row.getCost());
        assertEquals("platform", row.getCostSource());
    }

    @Test
    @DisplayName("OpenRouter 记账有延迟：重采样直到数字变动")
    void retriesUntilUsageMoves() {
        accountant.setBaselineForTest(new BigDecimal("1.00"));
        usage(1.00); // 还没记上
        usage(1.00);
        usage(1.02); // 记上了
        accountant.reconcile(7L);

        assertEquals(0, new BigDecimal("0.02").compareTo(row.getCost()));
    }

    @Test
    @DisplayName("采样一直不动：不写 cost（宁可留空也不写假数字）")
    void noMovementLeavesCostNull() {
        accountant.setBaselineForTest(new BigDecimal("1.00"));
        for (int i = 0; i < 6; i++) usage(1.00);
        accountant.reconcile(7L);

        verify(repository, never()).save(any());
        assertNull(row.getCost());
    }

    @Test
    @DisplayName("采样失败（断网/被禁用）：静默跳过，绝不影响对话")
    void probeFailureIsSilent() {
        accountant.setBaselineForTest(new BigDecimal("1.00"));
        // replies 为空 → 桩返回 NETWORK_FAILURE
        assertDoesNotThrow(() -> accountant.reconcile(7L));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("未连接账户（拿不到平台 key）：跳过，不发请求")
    void skipsWhenNoPlatformKey() {
        when(channel.apiKey()).thenThrow(new IllegalStateException("not connected"));
        accountant.setBaselineForTest(new BigDecimal("1.00"));
        assertDoesNotThrow(() -> accountant.reconcile(7L));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("换账户必须重置基线：否则两把 key 的累计差会整个记到下一条消息头上")
    void resetBaselineDropsPreviousKeysCumulative() {
        // 账户 A 的累计是 0.02，账户 B 的累计是 5.00
        accountant.setBaselineForTest(new BigDecimal("0.02"));
        accountant.resetBaseline();

        usage(5.00);
        accountant.reconcile(7L);
        // 重置后第一次只重新建基线，不会把 4.98 写成这条消息的花费
        verify(repository, never()).save(any());
        assertNull(row.getCost());

        usage(5.01);
        accountant.reconcile(7L);
        assertEquals(0, new BigDecimal("0.01").compareTo(row.getCost()), "cost=" + row.getCost());
    }

    @Test
    @DisplayName("请求前建基线：随后第一条消息就能算出差分，不再永久「待结算」")
    void ensureBaselineMakesFirstMessageBillable() throws Exception {
        usage(2.00); // ensureBaseline 采到的
        accountant.ensureBaselineAsync();
        // 单线程 worker：等基线任务跑完
        for (int i = 0; i < 100 && !replies.isEmpty(); i++) Thread.sleep(10);

        usage(2.05); // 这条消息之后的累计
        accountant.reconcile(7L);
        assertEquals(0, new BigDecimal("0.05").compareTo(row.getCost()), "cost=" + row.getCost());
    }
}
