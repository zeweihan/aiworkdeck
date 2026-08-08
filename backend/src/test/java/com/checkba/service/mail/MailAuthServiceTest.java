package com.checkba.service.mail;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.auth.VerificationCodeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailAuthServiceTest {

    /** 记录发信而不真发；同时充当「已配置的通道」。 */
    private static final class RecordingGateway implements MailGateway {
        final List<String[]> sent = new ArrayList<>();
        boolean fail;

        @Override public String name() { return "recording"; }
        @Override public boolean enabled() { return true; }
        @Override public boolean supports(String email) { return true; }

        @Override public void send(String to, String subject, String text) {
            if (fail) throw new IllegalArgumentException("邮件发送失败，请稍后重试");
            sent.add(new String[]{to, subject, text});
        }

        /** 从最近一封信里抠出 6 位验证码。 */
        String lastCode() {
            String body = sent.get(sent.size() - 1)[2];
            return body.replaceAll("(?s).*验证码是：(\\d{6}).*", "$1");
        }
    }

    private static User user(long id, String verifiedEmail) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setVerifiedEmail(verifiedEmail);
        return u;
    }

    private record Fixture(MailAuthService svc, RecordingGateway gw, UserRepository repo) {
    }

    private static Fixture fixture(boolean localMode, boolean passwordless) {
        RecordingGateway gw = new RecordingGateway();
        UserRepository repo = mock(UserRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MailAuthService svc = new MailAuthService(
                new VerificationCodeStore(), new MailRouter(List.of(gw)), repo, localMode, passwordless);
        return new Fixture(svc, gw, repo);
    }

    private static Fixture fixture() {
        return fixture(false, true);
    }

    @Test
    @DisplayName("local-mode 恒未启用；无可用通道时也未启用")
    void activeGating() {
        assertFalse(fixture(true, true).svc().active(), "local-mode 必须旁路");
        assertTrue(fixture().svc().active());

        MailRouter empty = new MailRouter(List.of());
        assertFalse(new MailAuthService(new VerificationCodeStore(), empty,
                mock(UserRepository.class), false, true).active(), "没有通道就不算启用");
    }

    @Test
    @DisplayName("免密登录是独立开关，关掉后二次验证仍可用")
    void passwordlessHasItsOwnSwitch() {
        Fixture f = fixture(false, false);
        assertTrue(f.svc().active());
        assertFalse(f.svc().passwordlessActive());
        assertThrows(IllegalArgumentException.class, () -> f.svc().sendSigninCode("a@qq.com"));
        // 二次验证不受影响
        assertTrue(f.svc().requiresCode(user(1, "a@qq.com")));
    }

    @Test
    @DisplayName("只有绑过已验证邮箱的用户才走邮箱二次验证")
    void requiresCodeOnlyWhenVerifiedEmailBound() {
        MailAuthService svc = fixture().svc();
        assertTrue(svc.requiresCode(user(1, "a@qq.com")));
        assertFalse(svc.requiresCode(user(1, null)));
        assertFalse(svc.requiresCode(user(1, "")));
        assertFalse(svc.requiresCode(null));
    }

    @Test
    @DisplayName("登录验证码：发出后错码被拒、对码通过且一次性")
    void loginCodeLifecycle() {
        Fixture f = fixture();
        User u = user(1, "alice@gmail.com");

        assertEquals("a***@gmail.com", f.svc().sendLoginCode(u));
        assertEquals(1, f.gw().sent.size());
        assertEquals("alice@gmail.com", f.gw().sent.get(0)[0]);

        String code = f.gw().lastCode();
        assertThrows(IllegalArgumentException.class, () -> f.svc().verifyLoginCode(u, "000000"));
        assertDoesNotThrow(() -> f.svc().verifyLoginCode(u, code));
        assertThrows(IllegalArgumentException.class, () -> f.svc().verifyLoginCode(u, code),
                "验证成功即销毁，同一枚码不能用第二次");
    }

    @Test
    @DisplayName("发信失败要回收验证码，否则冷却期挡住用户立即重试")
    void rollsBackCodeWhenSendFails() {
        Fixture f = fixture();
        User u = user(1, "alice@gmail.com");
        f.gw().fail = true;
        assertThrows(IllegalArgumentException.class, () -> f.svc().sendLoginCode(u));

        // 冷却已回滚：可以立刻再发
        f.gw().fail = false;
        assertDoesNotThrow(() -> f.svc().sendLoginCode(u));
    }

    @Test
    @DisplayName("绑定：写入 verifiedEmail；资料邮箱为空才补，已填的不覆盖")
    void confirmBindWritesVerifiedEmailWithoutClobberingProfile() {
        Fixture f = fixture();
        User u = user(7, null);
        when(f.repo().findById(7L)).thenReturn(Optional.of(u));
        when(f.repo().findByVerifiedEmail(any())).thenReturn(Optional.empty());

        f.svc().sendBindCode(7L, "  Bob@QQ.com ");
        assertEquals("bob@qq.com", f.gw().sent.get(0)[0], "收件地址应已规范化");

        assertEquals("b***@qq.com", f.svc().confirmBind(7L, "bob@qq.com", f.gw().lastCode()));
        assertEquals("bob@qq.com", u.getVerifiedEmail());
        assertEquals("bob@qq.com", u.getEmail(), "资料邮箱原本为空，应顺手补上");

        // 换绑到新地址时不动已有的资料邮箱
        User u2 = user(8, null);
        u2.setEmail("self-written@example.com");
        when(f.repo().findById(8L)).thenReturn(Optional.of(u2));
        f.svc().sendBindCode(8L, "carol@163.com");
        f.svc().confirmBind(8L, "carol@163.com", f.gw().lastCode());
        assertEquals("carol@163.com", u2.getVerifiedEmail());
        assertEquals("self-written@example.com", u2.getEmail(), "用户自己填的资料邮箱不许被覆盖");
    }

    @Test
    @DisplayName("同一邮箱不能绑到两个账号")
    void rejectsEmailBoundByAnotherAccount() {
        Fixture f = fixture();
        when(f.repo().findByVerifiedEmail("taken@qq.com")).thenReturn(Optional.of(user(99, "taken@qq.com")));
        assertThrows(IllegalArgumentException.class, () -> f.svc().sendBindCode(7L, "taken@qq.com"));
        assertTrue(f.gw().sent.isEmpty(), "被占用就不该发出任何信");
    }

    @Test
    @DisplayName("免密登录：未注册的邮箱不发信但照常返回，不泄露该邮箱是否是用户")
    void signinDoesNotLeakRegistrationStatus() {
        Fixture f = fixture();
        when(f.repo().findByVerifiedEmail("stranger@gmail.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> f.svc().sendSigninCode("stranger@gmail.com"));
        assertTrue(f.gw().sent.isEmpty(), "未注册就不该真发信");

        // 错码与查无此人给同一句话
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> f.svc().verifySigninCode("stranger@gmail.com", "123456"));
        assertEquals("验证码错误或已过期", e.getMessage());
    }

    @Test
    @DisplayName("免密登录：已注册邮箱走通并换回账号")
    void signinReturnsAccountOnCorrectCode() {
        Fixture f = fixture();
        User u = user(5, "dave@gmail.com");
        when(f.repo().findByVerifiedEmail("dave@gmail.com")).thenReturn(Optional.of(u));

        f.svc().sendSigninCode("dave@gmail.com");
        assertEquals(1, f.gw().sent.size());
        assertSame(u, f.svc().verifySigninCode("dave@gmail.com", f.gw().lastCode()));
    }

    @Test
    @DisplayName("场景隔离：绑定码不能拿去免密登录（否则低权限操作可兑换成完整登录）")
    void scenesAreNotInterchangeable() {
        Fixture f = fixture();
        User u = user(5, "dave@gmail.com");
        when(f.repo().findById(5L)).thenReturn(Optional.of(u));
        when(f.repo().findByVerifiedEmail("dave@gmail.com")).thenReturn(Optional.of(u));

        f.svc().sendBindCode(5L, "dave@gmail.com");
        String bindCode = f.gw().lastCode();
        assertThrows(IllegalArgumentException.class,
                () -> f.svc().verifySigninCode("dave@gmail.com", bindCode));
    }

    @Test
    @DisplayName("邮箱格式不合法直接拒，不占用配额")
    void rejectsMalformedAddress() {
        Fixture f = fixture();
        for (String bad : List.of("", "foo", "foo@", "@qq.com", "foo@qq")) {
            assertThrows(IllegalArgumentException.class, () -> f.svc().sendSigninCode(bad), "应拒绝: [" + bad + "]");
        }
        assertTrue(f.gw().sent.isEmpty());
    }

    @Test
    @DisplayName("脱敏：单字符本地部分不泄露原字符")
    void masksEmail() {
        assertEquals("h***@gmail.com", MailAuthService.maskEmail("hanzewei@gmail.com"));
        assertEquals("***@qq.com", MailAuthService.maskEmail("a@qq.com"));
        assertEquals("", MailAuthService.maskEmail(null));
        assertEquals("", MailAuthService.maskEmail("no-at-sign"));
    }
}
