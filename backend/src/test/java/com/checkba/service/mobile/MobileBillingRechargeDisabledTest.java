package com.checkba.service.mobile;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.UserService;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.mobile.MobileBillingClient.BalanceResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 充值总开关<b>关着</b>（= application.yml 的生产默认值）时的行为（dev-board#425 复审 N1）。
 *
 * <p>本类刻意<b>不配</b> {@code mobile.billing.recharge-enabled}：它测的就是「什么都不配的
 * 服务器上，充值是关的」。开关开着的那一半在 {@link MobileBillingServiceTest}
 * （那边显式配了 {@code =true}）。两个类的属性集不同，因此各自一个 Spring 上下文——这是刻意
 * 付的代价：默认值是这条护栏的全部意义，用一个「传了 false 进构造器」的纯单测证不了它。
 *
 * <p>为什么要有这个开关：{@code POST /api/mobile/billing/recharge} 是全站唯一走
 * {@code create=true} 的调用方，随本期一起上线且是活的端点。任何持有有效 {@code X-Session-Id}
 * 的人直接打它，就会在官网建出一行含明文手机号的真账户并发注册赠额；而注销只删 Java 侧、
 * 不通知官网，App 自己建的账号 App 内删不掉——就是复审 C1 那条 App Store 5.1.1(v)，
 * 只是触发点从「打开设置页」搬到了「直接打这个端点」。四端没有充值界面 = 没人调，不是护栏。
 *
 * <p><b>开关要等 dev-board#434（官网账户注销传导）落地后才允许打开。</b>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-billing-off-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-billing-off-test-store"
})
@ActiveProfiles("desktop")
class MobileBillingRechargeDisabledTest {

    @Autowired
    private MobileBillingService service;
    @Autowired
    private AccountBindingRepository accountBindingRepository;
    @Autowired
    private UserService userService;

    @MockBean
    private MobileBillingClient billing;
    // 同 MobileBillingServiceTest：完整上下文会让 WebTools 尝试联网初始化搜索工具
    @MockBean
    private WebTools webTools;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private User phoneUser() {
        return userService.findOrCreateByPhone(String.format("137%08d", 20_000_000 + SEQ.incrementAndGet()))
                .user();
    }

    private void bind(Long userId, String accountId) {
        AccountBinding row = new AccountBinding();
        row.setUserId(userId);
        row.setExternalAccountId(accountId);
        row.setCreatedAt(LocalDateTime.now());
        accountBindingRepository.save(row);
    }

    @Test
    @DisplayName("默认关：下单在到达 create=true 之前短路成 DISABLED，不 resolve、不建号、不落绑定")
    void createRechargeIsDisabledBeforeReachingCreateTrue() {
        User u = phoneUser();

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> service.createRecharge(u.getId(), 5000L, "idem-off-0001"));

        assertEquals(MobileBillingKind.DISABLED, e.getKind());
        // 一个上游请求都没发出去——建号那一位当然更没被置过 true
        verify(billing, never()).resolveAccountId(any(), any(), anyBoolean());
        verify(billing, never()).createRecharge(anyString(), anyLong(), anyString());
        verifyNoInteractions(billing);
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isEmpty());
    }

    @Test
    @DisplayName("默认关：查单同样 DISABLED，不打上游")
    void queryRechargeIsDisabled() {
        User u = phoneUser();
        bind(u.getId(), "acct-off-query-" + u.getId());

        assertEquals(MobileBillingKind.DISABLED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.queryRecharge(u.getId(), "OT-off-1")).getKind());
        verifyNoInteractions(billing);
    }

    @Test
    @DisplayName("开关先于一切：参数怎么填都是 DISABLED，不会先报 idempotencyKey / 金额的错")
    void switchShortCircuitsBeforeArgumentValidation() {
        User u = phoneUser();
        bind(u.getId(), "acct-off-args-" + u.getId());

        for (Object[] bad : new Object[][]{{null, null}, {0L, "short"}, {-1L, "has space!!"}}) {
            assertEquals(MobileBillingKind.DISABLED,
                    assertThrows(MobileBillingFailureException.class,
                            () -> service.createRecharge(u.getId(), (Long) bad[0], (String) bad[1])).getKind());
        }
        assertEquals(MobileBillingKind.DISABLED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.queryRecharge(u.getId(), "非法单号!!")).getKind());
        verifyNoInteractions(billing);
    }

    @Test
    @DisplayName("余额不受这个开关影响：只读、create=false、本期就是要它能用")
    void balanceStillWorksWhileRechargeIsOff() {
        User u = phoneUser();
        String accountId = "acct-off-balance-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(12345L, "CNY", "paid"));

        assertEquals(12345L, service.balance(u.getId()).balanceCents());
        verify(billing).balance(accountId);
    }
}
