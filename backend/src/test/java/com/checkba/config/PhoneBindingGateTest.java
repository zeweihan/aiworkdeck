package com.checkba.config;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.auth.VerificationCodeStore;
import com.checkba.service.sms.SmsAuthService;
import com.checkba.service.sms.SmsService;
import com.checkba.service.sms.SmsTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 存量账号补绑手机号的三态闸（spec §5）。
 *
 * 口径与官网 {@code lib/phone-policy.ts} 的 {@code gateForUser()} 一一对应，两边别漂：
 * {@code OK} 放行 / {@code MUST_BIND} 放行但要立刻强制补绑 / {@code BLOCKED} 拒登。
 */
class PhoneBindingGateTest {

    private static final SmsTransport OK_TRANSPORT =
            (url, body, auth) -> new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");

    private static final LocalDate DEADLINE = LocalDate.parse("2026-09-30");

    /** 网关点亮的 SmsAuthService——否则 PhoneLoginGuard 在强制模式下拒绝构造。 */
    private static SmsAuthService activeSms() {
        return new SmsAuthService(
                List.of(new SmsService(OK_TRANSPORT, true, "ak", "sk", "sign", "tpl")),
                new VerificationCodeStore(), mock(UserRepository.class), false);
    }

    private static PhoneLoginGuard guard(boolean required, String deadline) {
        return new PhoneLoginGuard(activeSms(), required, false, deadline);
    }

    private static User withPhone(String phone) {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPhone(phone);
        return user;
    }

    @Test
    @DisplayName("没开强制：未绑号也一律放行——这条闸对团队服务器/私有部署默认不生效")
    void disabledLetsEveryoneThrough() {
        PhoneLoginGuard g = guard(false, "2026-09-30");
        assertEquals(PhoneLoginGuard.PhoneGate.OK, g.gateFor(withPhone(null), DEADLINE.plusYears(5)));
    }

    @Test
    @DisplayName("已绑号：期限前后都放行")
    void boundUserIsNeverGated() {
        PhoneLoginGuard g = guard(true, "2026-09-30");
        assertEquals(PhoneLoginGuard.PhoneGate.OK, g.gateFor(withPhone("13800000000"), DEADLINE.minusDays(1)));
        assertEquals(PhoneLoginGuard.PhoneGate.OK, g.gateFor(withPhone("13800000000"), DEADLINE.plusYears(5)));
    }

    @Test
    @DisplayName("期限内未绑号：放行但必须回 MUST_BIND——直接拒掉等于把存量用户当场锁死")
    void unboundBeforeDeadlineMustBind() {
        PhoneLoginGuard g = guard(true, "2026-09-30");
        assertEquals(PhoneLoginGuard.PhoneGate.MUST_BIND, g.gateFor(withPhone(null), DEADLINE.minusMonths(1)));
        assertEquals(PhoneLoginGuard.PhoneGate.MUST_BIND, g.gateFor(withPhone("   "), DEADLINE.minusMonths(1)),
                "空白串不算绑过号");
    }

    @Test
    @DisplayName("期限当天仍算期限内——按 23:59:59 收口，不是当天零点就锁人")
    void deadlineDayIsStillInside() {
        PhoneLoginGuard g = guard(true, "2026-09-30");
        assertEquals(PhoneLoginGuard.PhoneGate.MUST_BIND, g.gateFor(withPhone(null), DEADLINE));
    }

    @Test
    @DisplayName("期限次日起未绑号拒登")
    void unboundAfterDeadlineIsBlocked() {
        PhoneLoginGuard g = guard(true, "2026-09-30");
        assertEquals(PhoneLoginGuard.PhoneGate.BLOCKED, g.gateFor(withPhone(null), DEADLINE.plusDays(1)));
    }

    @Test
    @DisplayName("期限配错格式回落默认值，而不是静默变成无限期延期")
    void malformedDeadlineFallsBackToDefault() {
        PhoneLoginGuard g = guard(true, "not-a-date");
        assertEquals(LocalDate.parse(PhoneLoginGuard.DEFAULT_BINDING_DEADLINE), g.bindingDeadline());
        assertEquals(PhoneLoginGuard.PhoneGate.BLOCKED,
                g.gateFor(withPhone(null), LocalDate.parse(PhoneLoginGuard.DEFAULT_BINDING_DEADLINE).plusDays(1)));
    }

    @Test
    @DisplayName("local-mode 免登没有登录环节，这条闸不适用")
    void localModeIsExempt() {
        PhoneLoginGuard g = new PhoneLoginGuard(activeSms(), true, true, "2026-09-30");
        assertFalse(g.isRequired());
        assertEquals(PhoneLoginGuard.PhoneGate.OK, g.gateFor(withPhone(null), DEADLINE.plusYears(5)));
    }

    @Test
    @DisplayName("期限默认值与官网 lib/phone-policy.ts 的 DEFAULT_BINDING_DEADLINE 同值")
    void defaultDeadlineMatchesWebsite() {
        assertEquals("2026-09-30", PhoneLoginGuard.DEFAULT_BINDING_DEADLINE);
    }
}
