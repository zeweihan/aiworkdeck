package com.checkba.service.auth;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.sms.SmsAuthService;
import com.checkba.service.totp.TotpService;
import com.checkba.service.totp.TotpTestCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SecondFactorServiceTest {

    private UserRepository repo;
    private SmsAuthService sms;
    private com.checkba.service.mail.MailAuthService mail;
    private TotpService totp;
    private SecondFactorService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        sms = mock(SmsAuthService.class);
        mail = mock(com.checkba.service.mail.MailAuthService.class);
        totp = new TotpService();
        service = new SecondFactorService(totp, mail, sms, repo);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        when(repo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static String codeFor(String secret) {
        return TotpTestCodes.now(secret);
    }

    @Test
    @DisplayName("判定优先级：TOTP 优先于短信；都没有则不拦")
    void methodPriority() {
        User plain = user(1);
        when(sms.requiresCode(plain)).thenReturn(false);
        assertEquals(SecondFactorService.Method.NONE, service.required(plain));

        when(sms.requiresCode(plain)).thenReturn(true);
        assertEquals(SecondFactorService.Method.SMS, service.required(plain));

        plain.setTotpEnabled(true);
        plain.setTotpSecret(totp.newSecret());
        assertEquals(SecondFactorService.Method.TOTP, service.required(plain),
                "同时具备时必须选零成本无国界的 TOTP");

        assertEquals(SecondFactorService.Method.NONE, service.required(null));
    }

    @Test
    @DisplayName("邮箱优先于短信——这是成本决策：绑了邮箱就别再花钱发短信")
    void mailOutranksSms() {
        User u = user(1);
        when(sms.requiresCode(u)).thenReturn(true);
        when(mail.requiresCode(u)).thenReturn(true);
        assertEquals(SecondFactorService.Method.MAIL, service.required(u),
                "两者都可用时必须走邮箱，否则这个功能不省钱");

        when(mail.requiresCode(u)).thenReturn(false);
        assertEquals(SecondFactorService.Method.SMS, service.required(u), "没绑邮箱才回落短信");

        // TOTP 仍然压过两者
        when(mail.requiresCode(u)).thenReturn(true);
        u.setTotpEnabled(true);
        u.setTotpSecret(totp.newSecret());
        assertEquals(SecondFactorService.Method.TOTP, service.required(u));
    }

    @Test
    @DisplayName("邮箱分支的校验落到 MailAuthService，且提示目标是脱敏邮箱")
    void mailBranchDelegatesAndMasks() {
        User u = user(1);
        u.setVerifiedEmail("alice@gmail.com");
        when(mail.requiresCode(u)).thenReturn(true);

        assertEquals("a***@gmail.com", service.target(u));
        service.verify(u, "123456");
        verify(mail).verifyLoginCode(u, "123456");
        verify(sms, never()).verifyLoginCode(any(), any());
    }

    @Test
    @DisplayName("历史行的 totpEnabled 为 NULL 时视为未启用（升级期真机 500 的根因，勿回退为原始 boolean）")
    void legacyNullFlagReadsAsDisabled() {
        User legacy = user(1);
        assertNull(legacy.getTotpEnabledRaw(), "新建实体不应预置 false，否则等于给已有表加 NOT NULL 列");
        assertFalse(legacy.isTotpEnabled());
        when(sms.requiresCode(legacy)).thenReturn(false);
        assertEquals(SecondFactorService.Method.NONE, service.required(legacy));
    }

    @Test
    @DisplayName("启用标志为真但密钥缺失时不拦（半截数据不该把人锁在门外）")
    void enabledWithoutSecretDoesNotBlock() {
        User u = user(1);
        u.setTotpEnabled(true);
        u.setTotpSecret(null);
        when(sms.requiresCode(u)).thenReturn(false);
        assertEquals(SecondFactorService.Method.NONE, service.required(u));
    }

    @Test
    @DisplayName("绑定全流程：setup 未启用 → activate 验码后启用")
    void setupThenActivate() {
        User u = user(1);
        var setup = service.startSetup(1L, "AI WorkDeck");

        assertEquals(setup.secret(), u.getTotpSecret());
        assertFalse(u.isTotpEnabled(), "setup 之后尚未启用");
        assertTrue(setup.provisioningUri().contains("secret=" + setup.secret()));

        assertThrows(IllegalArgumentException.class, () -> service.activate(1L, "000000"));
        assertFalse(u.isTotpEnabled());

        service.activate(1L, codeFor(setup.secret()));
        assertTrue(u.isTotpEnabled());
    }

    @Test
    @DisplayName("已绑定时重复 setup 被拒（避免把生效中的密钥换掉）")
    void setupRejectedWhenAlreadyEnabled() {
        User u = user(1);
        u.setTotpEnabled(true);
        u.setTotpSecret(totp.newSecret());
        assertThrows(IllegalArgumentException.class, () -> service.startSetup(1L, "AI WorkDeck"));
    }

    @Test
    @DisplayName("重放拦截：同一枚码在有效期内只能用一次")
    void replayRejected() {
        User u = user(1);
        var setup = service.startSetup(1L, "AI WorkDeck");
        String code = codeFor(setup.secret());
        service.activate(1L, code);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.verify(u, code));
        assertTrue(e.getMessage().contains("已使用"));
    }

    @Test
    @DisplayName("verify：TOTP 正码放行并推进时间片；错码抛业务错误")
    void verifyTotp() {
        User u = user(1);
        var setup = service.startSetup(1L, "AI WorkDeck");
        u.setTotpEnabled(true);

        assertThrows(IllegalArgumentException.class, () -> service.verify(u, "000000"));
        assertDoesNotThrow(() -> service.verify(u, codeFor(setup.secret())));
        assertNotNull(u.getTotpLastUsedStep());
    }

    @Test
    @DisplayName("verify：SMS 分支委派给 SmsAuthService；NONE 分支直接放行")
    void verifyDelegatesAndSkips() {
        User u = user(1);
        when(sms.requiresCode(u)).thenReturn(true);
        service.verify(u, "123456");
        verify(sms).verifyLoginCode(u, "123456");

        when(sms.requiresCode(u)).thenReturn(false);
        clearInvocations(sms);
        assertDoesNotThrow(() -> service.verify(u, null));
        verify(sms, never()).verifyLoginCode(any(), anyString());
    }

    @Test
    @DisplayName("解绑必须带当前码：错码拒绝，正码才清空密钥")
    void disableRequiresCurrentCode() {
        User u = user(1);
        var setup = service.startSetup(1L, "AI WorkDeck");
        service.activate(1L, codeFor(setup.secret()));

        assertThrows(IllegalArgumentException.class, () -> service.disable(1L, "000000"));
        assertTrue(u.isTotpEnabled(), "错码不得摘掉二次验证");

        // 换一个未被 activate 消费过的时间片，避免撞上重放判据
        String next = TotpTestCodes.stepsFromNow(setup.secret(), 1);
        service.disable(1L, next);
        assertFalse(u.isTotpEnabled());
        assertNull(u.getTotpSecret());
        assertNull(u.getTotpLastUsedStep());
    }

    @Test
    @DisplayName("未绑定时解绑是幂等空操作，不报错")
    void disableWhenNotEnabledIsNoop() {
        user(1);
        assertDoesNotThrow(() -> service.disable(1L, null));
    }

    @Test
    @DisplayName("管理员重置：无需验证码即可清除（认证器丢失的唯一出路）")
    void adminResetClears() {
        User u = user(1);
        var setup = service.startSetup(1L, "AI WorkDeck");
        service.activate(1L, codeFor(setup.secret()));

        service.resetByAdmin(1L);
        assertFalse(u.isTotpEnabled());
        assertNull(u.getTotpSecret());
    }

    @Test
    @DisplayName("target：短信回脱敏号，TOTP 回空串（码不在服务端）")
    void targetShape() {
        User u = user(1);
        u.setPhone("13800000000");
        when(sms.requiresCode(u)).thenReturn(true);
        assertEquals("138****0000", service.target(u));

        u.setTotpEnabled(true);
        u.setTotpSecret(totp.newSecret());
        assertEquals("", service.target(u));
    }
}
