// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.UserService;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.mobile.MobileBillingClient.BalanceResult;
import com.checkba.service.mobile.MobileBillingClient.MobileBillingException;
import com.checkba.service.mobile.MobileBillingClient.RechargeOrder;
import com.checkba.service.mobile.MobileBillingClient.RechargeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 手机端统一账户余额/充值服务（dev-board#425，spec §3.2）：账户桥接的三条红线
 * （只用服务端已验证身份 resolve、无身份拒、绝不改绑）、幂等键必须客户端传、
 * 官网不可达的降级、余额缓存按 userId。
 *
 * <p>装配同 {@link MobileTransferServiceTest} 的 H2 配方；{@link MobileBillingClient}
 * 用 @MockBean 换成可控桩，不真的打网络。审核账号旁路在本上下文里<b>开着</b>
 * （auth.review-account.*），钉的是 dev-board#661 之后的口径：审核账号<b>不再</b>被特殊对待，
 * 与普通用户走同一条路（旁路关着的话这条断言就成了空跑）。
 *
 * <p><b>本类把充值总开关打开</b>（{@code mobile.billing.recharge-enabled=true}，复审 N1），
 * 测的是「开关开着时的行为」——顺带钉住这个配置键真的能把功能打开（键名写错的话下面所有
 * 充值用例会一起变成 DISABLED）。生产默认是<b>关</b>，那一半在
 * {@link MobileBillingRechargeDisabledTest}（刻意不配这个键，走 application.yml 的默认值）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-billing-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-billing-test-store",
        "auth.review-account.identity=appreview@example.com,13800138000",
        "auth.review-account.code=246813",
        "mobile.billing.recharge-enabled=true"
})
@ActiveProfiles("desktop")
class MobileBillingServiceTest {

    @Autowired
    private MobileBillingService service;
    @Autowired
    private AccountBindingRepository accountBindingRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;

    @MockBean
    private MobileBillingClient billing;
    // 完整上下文会让 WebTools 尝试联网初始化搜索工具，同 MobileTransferServiceTest 一样 mock 掉
    @MockBean
    private WebTools webTools;

    /** 每个用例用独立的手机号/邮箱/accountId：上下文按配置缓存复用，同一个 H2 贯穿全类。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        reset(billing);
    }

    private User phoneUser() {
        return userService.findOrCreateByPhone(nextPhone()).user();
    }

    private static String nextPhone() {
        return String.format("139%08d", 10_000_000 + SEQ.incrementAndGet());
    }

    private void bind(Long userId, String accountId) {
        AccountBinding row = new AccountBinding();
        row.setUserId(userId);
        row.setExternalAccountId(accountId);
        row.setCreatedAt(LocalDateTime.now());
        accountBindingRepository.save(row);
    }

    // ==================== 桥接 ====================

    @Test
    @DisplayName("未绑定用户首次调用：拿服务端 User 上的手机号 resolve，并写入 account_binding")
    void firstCallResolvesByServerSidePhoneAndCreatesBinding() {
        User u = phoneUser();
        String accountId = "acct-first-" + u.getId();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false))).thenReturn(accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(12345L, "CNY", "pro"));

        BalanceResult r = service.balance(u.getId());

        assertEquals(12345L, r.balanceCents());
        assertEquals("CNY", r.currency());
        assertEquals("pro", r.plan());
        // resolve 的入参只能来自服务端 User 实体（手机号优先，email 位必须是 null）
        verify(billing).resolveAccountId(u.getPhone(), null, false);
        assertEquals(accountId,
                accountBindingRepository.findByUserId(u.getId()).orElseThrow().getExternalAccountId());
    }

    @Test
    @DisplayName("只有已验证邮箱的用户：按邮箱 resolve（phone 位为 null）")
    void emailOnlyUserResolvesByVerifiedEmail() {
        String email = "bill" + SEQ.incrementAndGet() + "@example.com";
        User u = userService.findOrCreateByEmail(email).user();
        String accountId = "acct-mail-" + u.getId();
        when(billing.resolveAccountId(isNull(), eq(email), eq(false))).thenReturn(accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(0L, "USD", null));

        assertEquals("USD", service.balance(u.getId()).currency());
        verify(billing).resolveAccountId(null, email, false);
    }

    @Test
    @DisplayName("已绑定用户：直接复用绑定，不再 resolve")
    void existingBindingIsReusedWithoutResolve() {
        User u = phoneUser();
        String accountId = "acct-bound-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(500L, "CNY", null));

        assertEquals(500L, service.balance(u.getId()).balanceCents());
        verify(billing, never()).resolveAccountId(any(), any(), anyBoolean());
    }

    /**
     * dev-board#661（2026-09-15 App Review 2.1(b)）：审核演示账号原先在 balance/createRecharge
     * 两条路上被 {@code requireNotReviewAccount} 一律拒掉，客户端按契约把余额行与充值入口整行隐藏，
     * 审核员因此「找不到内购」。现在它没有任何特殊分支——这条用例钉的就是「和普通用户完全一样」：
     * 读余额按已验证邮箱 {@code create=false} resolve，点充值 {@code create=true} 开户并落绑定。
     * 风险改由配置承接（审核身份必须是无真实钱包的专用邮箱），不再由这一层拦。
     */
    @Test
    @DisplayName("审核账号：不再有特殊分支，与普通用户走同一条桥接/充值路径")
    void reviewAccountGoesThroughTheOrdinaryPath() {
        User byPhone = userService.findOrCreateByPhone("13800138000").user();
        User byMail = userService.findOrCreateReviewAccount("appreview@example.com");

        String phoneAccount = "acct-review-phone-" + byPhone.getId();
        when(billing.resolveAccountId(eq(byPhone.getPhone()), isNull(), eq(false))).thenReturn(phoneAccount);
        when(billing.balance(phoneAccount)).thenReturn(new BalanceResult(0L, "CNY", null));
        assertEquals(0L, service.balance(byPhone.getId()).balanceCents());
        assertEquals(phoneAccount,
                accountBindingRepository.findByUserId(byPhone.getId()).orElseThrow().getExternalAccountId());

        // 邮箱形态的审核账号：读余额查无账户 → 和任何新用户一样是 NOT_CONNECTED，不是 REVIEW_ACCOUNT
        when(billing.resolveAccountId(isNull(), eq("appreview@example.com"), eq(false)))
                .thenThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "按该身份查无账户"));
        assertEquals(MobileBillingKind.NOT_CONNECTED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(byMail.getId())).getKind());
        assertTrue(accountBindingRepository.findByUserId(byMail.getId()).isEmpty());

        // 点充值：create=true，按审核邮箱开户并落绑定——审核员能真的走完内购前的这一步
        String mailAccount = "acct-review-mail-" + byMail.getId();
        when(billing.resolveAccountId(isNull(), eq("appreview@example.com"), eq(true))).thenReturn(mailAccount);
        when(billing.createRecharge(mailAccount, 5000L, "idem-review-0001", "appstore", "credits.cny.50", null))
                .thenReturn(new RechargeOrder("native", "OT-review", 5000L, null, null, null,
                        null, null, null, "token-review"));

        RechargeOrder order = service.createRecharge(byMail.getId(), 5000L, "idem-review-0001",
                "appstore", "credits.cny.50", null);

        assertEquals("OT-review", order.outTradeNo());
        assertEquals(mailAccount,
                accountBindingRepository.findByUserId(byMail.getId()).orElseThrow().getExternalAccountId());
    }

    @Test
    @DisplayName("User 既无手机号也无已验证邮箱：拒绝，且不回落任何机器级账户")
    void userWithoutVerifiedIdentityIsRefused() {
        User u = userService.registerExternal("billnoid" + SEQ.incrementAndGet(), "无身份");

        String msg = assertThrows(IllegalArgumentException.class,
                () -> service.balance(u.getId())).getMessage();
        assertTrue(msg.contains("已验证的手机号或邮箱"), msg);
        verifyNoInteractions(billing);
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isEmpty());
    }

    @Test
    @DisplayName("resolve 出来的 accountId 已绑给别的用户：拒绝，绝不改绑")
    void accountAlreadyBoundToAnotherUserIsRefused() {
        User owner = phoneUser();
        User other = phoneUser();
        String accountId = "acct-shared-" + owner.getId();
        bind(owner.getId(), accountId);
        when(billing.resolveAccountId(eq(other.getPhone()), isNull(), eq(false))).thenReturn(accountId);

        String msg = assertThrows(IllegalArgumentException.class,
                () -> service.balance(other.getId())).getMessage();
        assertTrue(msg.contains("已与另一个登录账号关联"), msg);
        // 原绑定一个字都没动
        assertEquals(owner.getId(),
                accountBindingRepository.findByExternalAccountId(accountId).orElseThrow().getUserId());
        assertTrue(accountBindingRepository.findByUserId(other.getId()).isEmpty());
    }

    // ==================== 充值 ====================

    @Test
    @DisplayName("idempotencyKey 缺失/非法：报错，服务端绝不代生成，也不发请求")
    void missingIdempotencyKeyIsRefusedAndNeverGenerated() {
        User u = phoneUser();
        bind(u.getId(), "acct-idem-" + u.getId());

        for (String bad : new String[]{null, "", "   ", "short", "has space!!"}) {
            String msg = assertThrows(IllegalArgumentException.class,
                    () -> service.createRecharge(u.getId(), 5000L, bad, null, null, null)).getMessage();
            assertTrue(msg.contains("idempotencyKey"), msg);
        }
        verify(billing, never()).createRecharge(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("金额必须是正整数分")
    void nonPositiveAmountIsRefused() {
        User u = phoneUser();
        bind(u.getId(), "acct-amt-" + u.getId());

        for (Long bad : new Long[]{null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createRecharge(u.getId(), bad, "idem-amount-01", null, null, null));
        }
        verify(billing, never()).createRecharge(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("建单：客户端传来的幂等键原样上行")
    void clientIdempotencyKeyIsPassedThrough() {
        User u = phoneUser();
        String accountId = "acct-pay-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.createRecharge(accountId, 5000L, "idem-abc-0001", null, null, null))
                .thenReturn(new RechargeOrder("qrcode", "OT123", 5000L, "weixin://x", null, null,
                        null, null, null, null));

        RechargeOrder order = service.createRecharge(u.getId(), 5000L, "idem-abc-0001",
                null, null, null);

        assertEquals("qrcode", order.present());
        assertEquals("OT123", order.outTradeNo());
        assertEquals(5000L, order.amountCents());
        verify(billing).createRecharge(accountId, 5000L, "idem-abc-0001", null, null, null);
    }

    // ==================== 小程序虚拟支付通道（dev-board#427） ====================

    @Test
    @DisplayName("channel=wxvp：productId / wxCode 原样上行，signData 三兄弟原样带回")
    void wxvpChannelIsPassedThroughBothWays() {
        User u = phoneUser();
        String accountId = "acct-wxvp-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.createRecharge(accountId, 1000L, "idem-wxvp-0001",
                "wxvp", "credits_cny_10", "0a1b2c3d4e"))
                .thenReturn(new RechargeOrder("virtual", "OT-wxvp", 1000L, null, null, null,
                        "{\"offerId\":\"1450637533\"}", "paysig-hex", "sig-hex", null));

        RechargeOrder order = service.createRecharge(u.getId(), 1000L, "idem-wxvp-0001",
                "wxvp", "credits_cny_10", "0a1b2c3d4e");

        assertEquals("virtual", order.present());
        assertEquals("paysig-hex", order.paySig());
        assertEquals("sig-hex", order.signature());
        assertNull(order.codeUrl());
        verify(billing).createRecharge(accountId, 1000L, "idem-wxvp-0001",
                "wxvp", "credits_cny_10", "0a1b2c3d4e");
    }

    @Test
    @DisplayName("channel=wxvp 缺 productId / wxCode 或 productId 形态不对：拒绝，不发上游")
    void wxvpRequiresProductIdAndWxCode() {
        User u = phoneUser();
        bind(u.getId(), "acct-wxvp-bad-" + u.getId());

        // {productId, wxCode}
        String[][] bad = {
                {null, "0a1b2c3d4e"}, {"", "0a1b2c3d4e"}, {"Credits CNY 10", "0a1b2c3d4e"},
                {"credits_cny_10", null}, {"credits_cny_10", "   "}};
        for (String[] args : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createRecharge(u.getId(), 1000L, "idem-wxvp-bad1",
                            "wxvp", args[0], args[1]));
        }
        verify(billing, never()).createRecharge(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("不认识的 channel：拒绝，绝不静默退回站点默认通道")
    void unknownChannelIsRefusedNotSilentlyDowngraded() {
        User u = phoneUser();
        bind(u.getId(), "acct-wxvp-ch-" + u.getId());

        for (String ch : new String[]{"alipay", "WXVP", "wxpay"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createRecharge(u.getId(), 1000L, "idem-wxvp-ch01",
                            ch, "credits_cny_10", "0a1b2c3d4e"));
        }
        verify(billing, never()).createRecharge(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("金额与档位是否相符不在这里判：官网的 product_mismatch 原样译成 REJECTED")
    void productMismatchFromUpstreamStaysRejected() {
        User u = phoneUser();
        String accountId = "acct-wxvp-mm-" + u.getId();
        bind(u.getId(), accountId);
        doThrow(new MobileBillingException(MobileBillingKind.REJECTED,
                "充值档位与金额不符，请更新小程序后重试", "product_mismatch"))
                .when(billing).createRecharge(accountId, 9900L, "idem-wxvp-mm01",
                        "wxvp", "credits_cny_10", "0a1b2c3d4e");

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> service.createRecharge(u.getId(), 9900L, "idem-wxvp-mm01",
                        "wxvp", "credits_cny_10", "0a1b2c3d4e"));
        assertEquals(MobileBillingKind.REJECTED, e.getKind());
        assertEquals("充值档位与金额不符，请更新小程序后重试", e.getMessage());
    }

    // ==================== iOS 内购通道（dev-board#426） ====================

    @Test
    @DisplayName("channel=appstore：带点号的 productId 放行、wxCode 不上行，appAccountToken 原样带回")
    void appstoreChannelPassesProductIdAndReturnsToken() {
        User u = phoneUser();
        String accountId = "acct-ios-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.createRecharge(accountId, 5000L, "idem-ios-0001",
                "appstore", "credits.cny.50", null))
                .thenReturn(new RechargeOrder("native", "OT-ios", 5000L, null, null, null,
                        null, null, null, "3f2504e0-4f89-11d3-9a0c-0305e82c3301"));

        RechargeOrder order = service.createRecharge(u.getId(), 5000L, "idem-ios-0001",
                "appstore", "credits.cny.50", null);

        assertEquals("native", order.present());
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", order.appAccountToken());
        assertNull(order.codeUrl());
        assertNull(order.signData());
        verify(billing).createRecharge(accountId, 5000L, "idem-ios-0001",
                "appstore", "credits.cny.50", null);
    }

    @Test
    @DisplayName("channel=appstore 带了 wxCode：那是微信那条路的凭证，不上行也不报错")
    void appstoreIgnoresWxCode() {
        User u = phoneUser();
        String accountId = "acct-ios-nowx-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.createRecharge(accountId, 5000L, "idem-ios-0002",
                "appstore", "credits.cny.50", null))
                .thenReturn(new RechargeOrder("native", "OT-ios-2", 5000L, null, null, null,
                        null, null, null, "token-2"));

        assertEquals("OT-ios-2", service.createRecharge(u.getId(), 5000L, "idem-ios-0002",
                "appstore", "credits.cny.50", "0a1b2c3d4e").outTradeNo());

        verify(billing).createRecharge(accountId, 5000L, "idem-ios-0002",
                "appstore", "credits.cny.50", null);
    }

    @Test
    @DisplayName("channel=appstore 缺 productId 或形态不对：拒绝，不发上游（wxvp 那条仍不吃点号）")
    void appstoreRequiresWellFormedProductId() {
        User u = phoneUser();
        bind(u.getId(), "acct-ios-bad-" + u.getId());

        for (String bad : new String[]{null, "", "   ", "Credits.CNY.50", "credits cny 50", "credits/cny/50"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createRecharge(u.getId(), 5000L, "idem-ios-bad1",
                            "appstore", bad, null));
        }
        // 点号是 appstore 专有的放宽，别顺手漏进微信道具那条
        assertThrows(IllegalArgumentException.class,
                () -> service.createRecharge(u.getId(), 1000L, "idem-ios-bad2",
                        "wxvp", "credits.cny.10", "0a1b2c3d4e"));
        verify(billing, never()).createRecharge(any(), anyLong(), any(), any(), any(), any());
    }

    // ==================== 内购交易确认（dev-board#426） ====================

    @Test
    @DisplayName("confirm：outTradeNo 与 JWS 原样上行；到账即作废该用户的余额缓存")
    void confirmAppstorePassesThroughAndInvalidatesBalance() {
        User u = phoneUser();
        String accountId = "acct-ios-cf-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(100L, "CNY", "free"));
        when(billing.confirmAppstore(accountId, "OT-ios-1", "jws.a.b"))
                .thenReturn(new RechargeStatus("paid", true, 5000L));

        service.balance(u.getId());
        RechargeStatus s = service.confirmAppstore(u.getId(), "OT-ios-1", "jws.a.b");

        assertEquals("paid", s.status());
        assertTrue(s.paid());
        assertEquals(5000L, s.amountCents());
        verify(billing).confirmAppstore(accountId, "OT-ios-1", "jws.a.b");
        // 缓存被作废：下一次读余额要打真源
        service.balance(u.getId());
        verify(billing, times(2)).balance(accountId);
    }

    @Test
    @DisplayName("confirm：outTradeNo 可缺省（App 被杀后的重放），空串一律归一成 null")
    void confirmAppstoreAcceptsMissingOutTradeNo() {
        User u = phoneUser();
        String accountId = "acct-ios-cf2-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.confirmAppstore(accountId, null, "jws.a.b"))
                .thenReturn(new RechargeStatus("paid", true, 5000L));

        assertTrue(service.confirmAppstore(u.getId(), null, "jws.a.b").paid());
        assertTrue(service.confirmAppstore(u.getId(), "  ", "jws.a.b").paid());
        verify(billing, times(2)).confirmAppstore(accountId, null, "jws.a.b");
    }

    @Test
    @DisplayName("confirm：JWS 必填且有长度上限，outTradeNo 给了就要合形态——都不发上游")
    void confirmAppstoreValidatesArguments() {
        User u = phoneUser();
        bind(u.getId(), "acct-ios-cf3-" + u.getId());

        for (String bad : new String[]{null, "", "   ", "x".repeat(16 * 1024 + 1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.confirmAppstore(u.getId(), "OT-ios-1", bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmAppstore(u.getId(), "非法单号!!", "jws.a.b"));
        verify(billing, never()).confirmAppstore(any(), any(), any());

        // 正好卡在上限上要过：上限是防灌大 body，不是拒绝真实交易
        String accountId = accountBindingRepository.findByUserId(u.getId()).orElseThrow()
                .getExternalAccountId();
        String maxJws = "x".repeat(16 * 1024);
        when(billing.confirmAppstore(accountId, null, maxJws))
                .thenReturn(new RechargeStatus("paid", true, 5000L));
        assertTrue(service.confirmAppstore(u.getId(), null, maxJws).paid());
    }

    @Test
    @DisplayName("confirm：官网的 REJECTED / NOT_FOUND 原样带判别位与文案落到信封")
    void confirmAppstoreFailuresKeepTheirKind() {
        User u = phoneUser();
        String accountId = "acct-ios-cf4-" + u.getId();
        bind(u.getId(), accountId);

        doThrow(new MobileBillingException(MobileBillingKind.REJECTED,
                "该交易已使用", "transaction_reused"))
                .when(billing).confirmAppstore(accountId, "OT-ios-used", "jws.a.b");
        MobileBillingFailureException reused = assertThrows(MobileBillingFailureException.class,
                () -> service.confirmAppstore(u.getId(), "OT-ios-used", "jws.a.b"));
        assertEquals(MobileBillingKind.REJECTED, reused.getKind());
        assertEquals("该交易已使用", reused.getMessage());

        doThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND,
                "未找到对应的统一账户", "order_not_found"))
                .when(billing).confirmAppstore(accountId, "OT-ios-gone", "jws.a.b");
        MobileBillingFailureException missing = assertThrows(MobileBillingFailureException.class,
                () -> service.confirmAppstore(u.getId(), "OT-ios-gone", "jws.a.b"));
        assertEquals(MobileBillingKind.NOT_FOUND, missing.getKind());
        // 查单/确认的 404 分不出「账户没了」与「单号不是你的」，一律不动绑定行
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isPresent());
    }

    // ==================== 缓存与降级 ====================

    @Test
    @DisplayName("余额按 userId 缓存；查单查到已到账即作废该用户的缓存")
    void balanceIsCachedPerUserAndInvalidatedWhenPaid() {
        User u = phoneUser();
        String accountId = "acct-cache-" + u.getId();
        bind(u.getId(), accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(100L, "CNY", null));

        service.balance(u.getId());
        service.balance(u.getId());
        verify(billing, times(1)).balance(accountId);

        // 未到账不动缓存
        when(billing.queryRecharge(accountId, "OT-pending")).thenReturn(new RechargeStatus("pending", false, 5000L));
        service.queryRecharge(u.getId(), "OT-pending");
        service.balance(u.getId());
        verify(billing, times(1)).balance(accountId);

        // 到账后作废，下一次必须打真源
        when(billing.queryRecharge(accountId, "OT-paid")).thenReturn(new RechargeStatus("paid", true, 5000L));
        service.queryRecharge(u.getId(), "OT-paid");
        service.balance(u.getId());
        verify(billing, times(2)).balance(accountId);
    }

    @Test
    @DisplayName("缓存不串户：A 的余额不会被 B 读到")
    void balanceCacheIsNotSharedBetweenUsers() {
        User a = phoneUser();
        User b = phoneUser();
        bind(a.getId(), "acct-a-" + a.getId());
        bind(b.getId(), "acct-b-" + b.getId());
        when(billing.balance("acct-a-" + a.getId())).thenReturn(new BalanceResult(111L, "CNY", null));
        when(billing.balance("acct-b-" + b.getId())).thenReturn(new BalanceResult(222L, "CNY", null));

        assertEquals(111L, service.balance(a.getId()).balanceCents());
        assertEquals(222L, service.balance(b.getId()).balanceCents());
        assertEquals(111L, service.balance(a.getId()).balanceCents());
    }

    @Test
    @DisplayName("官网不可达：给可读错误，绝不用余额 0 冒充，也不写缓存")
    void upstreamUnavailableIsReadableErrorNotZeroBalance() {
        User u = phoneUser();
        String accountId = "acct-down-" + u.getId();
        bind(u.getId(), accountId);
        // 这里必须用 doThrow/doReturn 改桩：when(...) 会真的再调一次已被打桩成"抛异常"的方法
        doThrow(new MobileBillingException(
                MobileBillingKind.UNAVAILABLE, "账户服务暂不可用，请稍后再试"))
                .when(billing).balance(accountId);

        assertEquals("账户服务暂不可用，请稍后再试",
                assertThrows(IllegalArgumentException.class, () -> service.balance(u.getId())).getMessage());
        // 失败不进缓存：恢复后第一次调用就应该拿到真值
        doReturn(new BalanceResult(777L, "CNY", null)).when(billing).balance(accountId);
        assertEquals(777L, service.balance(u.getId()).balanceCents());
    }

    @Test
    @DisplayName("本服务器未开通（DISABLED）：可读错误，不是 500")
    void disabledBillingIsReadableError() {
        User u = phoneUser();
        bind(u.getId(), "acct-off-" + u.getId());
        when(billing.balance(anyString())).thenThrow(new MobileBillingException(
                MobileBillingKind.DISABLED, "此服务器未开通统一账户充值"));

        assertEquals("此服务器未开通统一账户充值",
                assertThrows(IllegalArgumentException.class, () -> service.balance(u.getId())).getMessage());
    }

    @Test
    @DisplayName("resolve 阶段官网不可达：不建绑定，下次可重试")
    void resolveFailureLeavesNoBinding() {
        User u = phoneUser();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false))).thenThrow(new MobileBillingException(
                MobileBillingKind.UNAVAILABLE, "账户服务暂不可用，请稍后再试"));

        assertThrows(IllegalArgumentException.class, () -> service.balance(u.getId()));
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isEmpty());
    }

    // ==================== 读余额永不建号（复审 C1） ====================

    @Test
    @DisplayName("读余额一律 create=false：官网查无此账户 → NOT_CONNECTED，不建号也不落绑定")
    void balanceNeverCreatesAccountUpstream() {
        User u = phoneUser();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false)))
                .thenThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "未找到对应的统一账户",
                        "account_not_found"));

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> service.balance(u.getId()));
        // resolve 的「按这个身份没有账户」是「还没关联」，不是「绑定指向的账户没了」
        assertEquals(MobileBillingKind.NOT_CONNECTED, e.getKind());
        // 建号那一位从来没有被置过 true
        verify(billing, never()).resolveAccountId(any(), any(), eq(true));
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isEmpty());
    }

    @Test
    @DisplayName("查单也不建号（create=false）")
    void queryNeverCreatesAccountUpstream() {
        User u = phoneUser();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false)))
                .thenThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "未找到对应的统一账户",
                        "account_not_found"));

        assertEquals(MobileBillingKind.NOT_CONNECTED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.queryRecharge(u.getId(), "OT-x")).getKind());
        verify(billing, never()).resolveAccountId(any(), any(), eq(true));
    }

    @Test
    @DisplayName("只有「用户显式发起充值」这一条路允许官网建号：create=true")
    void onlyRechargeMayCreateAccountUpstream() {
        User u = phoneUser();
        String accountId = "acct-create-" + u.getId();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(true))).thenReturn(accountId);
        when(billing.createRecharge(accountId, 5000L, "idem-create-01", null, null, null))
                .thenReturn(new RechargeOrder("qrcode", "OT-create", 5000L, "weixin://x", null, null,
                        null, null, null, null));

        assertEquals("OT-create",
                service.createRecharge(u.getId(), 5000L, "idem-create-01", null, null, null)
                        .outTradeNo());
        verify(billing).resolveAccountId(u.getPhone(), null, true);
    }

    // ==================== 标识选择与回退 ====================

    @Test
    @DisplayName("手机号被官网按站点能力拒（REJECTED）：回退试已验证邮箱")
    void rejectedIdentityFallsBackToTheOther() {
        User u = bothIdentitiesUser();
        String accountId = "acct-fallback-" + u.getId();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false)))
                .thenThrow(new MobileBillingException(MobileBillingKind.REJECTED,
                        "充值请求被拒绝，请稍后重试或联系客服", "phone_not_supported_on_site"));
        when(billing.resolveAccountId(isNull(), eq(u.getVerifiedEmail()), eq(false))).thenReturn(accountId);
        when(billing.balance(accountId)).thenReturn(new BalanceResult(88L, "USD", "free"));

        assertEquals(88L, service.balance(u.getId()).balanceCents());
        verify(billing).resolveAccountId(u.getPhone(), null, false);
        verify(billing).resolveAccountId(null, u.getVerifiedEmail(), false);
    }

    @Test
    @DisplayName("手机号 NOT_FOUND 时绝不回退到邮箱：那是把用户悄悄关联到另一个官网账户")
    void notFoundIdentityDoesNotFallBack() {
        User u = bothIdentitiesUser();
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false)))
                .thenThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "未找到对应的统一账户",
                        "account_not_found"));

        assertEquals(MobileBillingKind.NOT_CONNECTED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(u.getId())).getKind());
        verify(billing, never()).resolveAccountId(isNull(), anyString(), anyBoolean());
        assertTrue(accountBindingRepository.findByUserId(u.getId()).isEmpty());
    }

    // ==================== 绑定自愈 ====================

    @Test
    @DisplayName("绑定指向的官网账户已注销：清掉绑定重解析，不再永久卡在「未找到」")
    void staleBindingSelfHeals() {
        User u = phoneUser();
        String dead = "acct-dead-" + u.getId();
        String fresh = "acct-fresh-" + u.getId();
        bind(u.getId(), dead);
        doThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "未找到对应的统一账户",
                "account_not_found")).when(billing).balance(dead);
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false))).thenReturn(fresh);
        doReturn(new BalanceResult(66L, "CNY", "paid")).when(billing).balance(fresh);

        assertEquals(66L, service.balance(u.getId()).balanceCents());
        assertEquals(fresh,
                accountBindingRepository.findByUserId(u.getId()).orElseThrow().getExternalAccountId());
    }

    @Test
    @DisplayName("自愈只重试一次：重解析回同一个死账户就如实报错，不死循环")
    void staleBindingHealRetriesOnlyOnce() {
        User u = phoneUser();
        String dead = "acct-dead2-" + u.getId();
        bind(u.getId(), dead);
        doThrow(new MobileBillingException(MobileBillingKind.NOT_FOUND, "未找到对应的统一账户",
                "account_not_found")).when(billing).balance(dead);
        when(billing.resolveAccountId(eq(u.getPhone()), isNull(), eq(false))).thenReturn(dead);

        assertEquals(MobileBillingKind.NOT_FOUND,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(u.getId())).getKind());
        verify(billing, times(2)).balance(dead);
    }

    // ==================== kind / outTradeNo 透传（复审 C2、C4） ====================

    @Test
    @DisplayName("已付单的 409：ALREADY_PAID 与 outTradeNo 一路带到服务层，不再被丢掉")
    void alreadyPaidKeepsKindAndOutTradeNo() {
        User u = phoneUser();
        String accountId = "acct-paid-" + u.getId();
        bind(u.getId(), accountId);
        doThrow(new MobileBillingException(MobileBillingKind.ALREADY_PAID,
                "这笔充值已经支付成功，请查看订单状态", "order_already_paid", "RECHARGE20260904"))
                .when(billing).createRecharge(accountId, 5000L, "idem-paid-0001", null, null, null);

        MobileBillingFailureException e = assertThrows(MobileBillingFailureException.class,
                () -> service.createRecharge(u.getId(), 5000L, "idem-paid-0001", null, null, null));
        assertEquals(MobileBillingKind.ALREADY_PAID, e.getKind());
        assertEquals("RECHARGE20260904", e.getOutTradeNo());
    }

    @Test
    @DisplayName("服务层自己判定的失败也带 kind：无身份 / 已绑给别人")
    void serviceLevelFailuresCarryKind() {
        User noId = userService.registerExternal("billkind" + SEQ.incrementAndGet(), "无身份");
        assertEquals(MobileBillingKind.NOT_CONNECTED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(noId.getId())).getKind());

        User owner = phoneUser();
        User other = phoneUser();
        String shared = "acct-kind-shared-" + owner.getId();
        bind(owner.getId(), shared);
        when(billing.resolveAccountId(eq(other.getPhone()), isNull(), eq(false))).thenReturn(shared);
        assertEquals(MobileBillingKind.REJECTED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(other.getId())).getKind());
    }

    @Test
    @DisplayName("上游 UNAVAILABLE / DISABLED 的 kind 原样透传，不被压成一句 message")
    void upstreamKindsArePassedThrough() {
        User u = phoneUser();
        String accountId = "acct-kind-" + u.getId();
        bind(u.getId(), accountId);

        doThrow(new MobileBillingException(MobileBillingKind.UNAVAILABLE, "账户服务暂不可用，请稍后再试"))
                .when(billing).balance(accountId);
        assertEquals(MobileBillingKind.UNAVAILABLE,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(u.getId())).getKind());

        doThrow(new MobileBillingException(MobileBillingKind.DISABLED, "此服务器未开通统一账户充值"))
                .when(billing).balance(accountId);
        assertEquals(MobileBillingKind.DISABLED,
                assertThrows(MobileBillingFailureException.class,
                        () -> service.balance(u.getId())).getKind());
    }

    /** 同时有手机号和已验证邮箱的用户（国际站存量形态）。 */
    private User bothIdentitiesUser() {
        String email = "both" + SEQ.incrementAndGet() + "@example.com";
        User u = userService.findOrCreateByEmail(email).user();
        u.setPhone(nextPhone());
        return userRepository.save(u);
    }
}
