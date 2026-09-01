package com.checkba.service.mail;

import com.checkba.config.ReviewAccountGate;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.UserService;
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
        UserService users = mock(UserService.class);
        // 建号那步默认回落到「按已验证邮箱查到的那个人」，这样老用例不必每个都去 stub；
        // 真要验建号行为的用例自己另建 fixture。
        when(users.findOrCreateByEmail(anyString())).thenAnswer(inv -> {
            String addr = inv.getArgument(0);
            User u = repo.findByVerifiedEmail(addr).orElseGet(() -> user(0, addr));
            return new UserService.EmailAccount(u, false);
        });
        MailAuthService svc = new MailAuthService(
                new VerificationCodeStore(), new MailRouter(List.of(gw)), repo, localMode, passwordless,
                ReviewAccountGate.disabled(), users);
        return new Fixture(svc, gw, repo);
    }

    private static Fixture fixture() {
        return fixture(false, true);
    }

    @Test
    @DisplayName("注册登录合一：未注册邮箱也发码，验过即建号")
    void unknownEmailGetsCodeAndAccountIsCreated() {
        RecordingGateway gw = new RecordingGateway();
        UserRepository repo = mock(UserRepository.class);
        UserService users = mock(UserService.class);
        User fresh = new User();
        fresh.setId(777L);
        when(users.findOrCreateByEmail("newcomer@example.com"))
                .thenReturn(new UserService.EmailAccount(fresh, true));

        VerificationCodeStore store = new VerificationCodeStore();
        MailAuthService svc = new MailAuthService(
                store, new MailRouter(List.of(gw)), repo, false, true,
                ReviewAccountGate.disabled(), users);

        // 未注册地址也该真的发出一封信（旧行为是静默不发）
        svc.sendSigninCode("newcomer@example.com");
        assertFalse(gw.sent.isEmpty(), "未注册邮箱也应收到验证码");

        // 拿到的码验过之后，账号被建出来
        String code = gw.lastCode();
        assertSame(fresh, svc.verifySigninCode("newcomer@example.com", code));
        verify(users).findOrCreateByEmail("newcomer@example.com");
    }

    @Test
    @DisplayName("审核账号：固定码放行并落到专用账号，不经验证码 store")
    void reviewAccountBypassesCodeStoreAndLandsOnItsOwnAccount() {
        MailGateway gw = new RecordingGateway();
        UserRepository repo = mock(UserRepository.class);
        UserService users = mock(UserService.class);
        User reviewer = new User();
        reviewer.setId(999L);
        reviewer.setUsername("appreview");
        when(users.findOrCreateReviewAccount("appreview@example.com")).thenReturn(reviewer);

        MailAuthService svc = new MailAuthService(
                new VerificationCodeStore(), new MailRouter(List.of(gw)), repo, false, true,
                new ReviewAccountGate("appreview@example.com", "246813"), users);

        // 没发过任何码，直接用固定码换账号
        assertSame(reviewer, svc.verifySigninCode("appreview@example.com", "246813"));

        // 码错就不该放行，也不该退化成「查已验证邮箱」
        assertThrows(IllegalArgumentException.class,
                () -> svc.verifySigninCode("appreview@example.com", "000000"));
        // 别的地址即使拿到那把码也不放行
        assertThrows(IllegalArgumentException.class,
                () -> svc.verifySigninCode("someoneelse@example.com", "246813"));
    }

    @Test
    @DisplayName("local-mode 恒未启用；无可用通道时也未启用")
    void activeGating() {
        assertFalse(fixture(true, true).svc().active(), "local-mode 必须旁路");
        assertTrue(fixture().svc().active());

        MailRouter empty = new MailRouter(List.of());
        assertFalse(new MailAuthService(new VerificationCodeStore(), empty,
                mock(UserRepository.class), false, true,
                ReviewAccountGate.disabled(), mock(UserService.class)).active(), "没有通道就不算启用");
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
    @DisplayName("免密登录：已注册与未注册的表现完全一致，读不出该邮箱是不是用户")
    void signinDoesNotLeakRegistrationStatus() {
        // 反枚举的不变量变了：以前靠「未注册不发信」，那反而是枚举器——对方从
        // 收没收到信就能读出结论。现在两者都发码、都能验过，真正没有差别可读。
        Fixture known = fixture();
        when(known.repo().findByVerifiedEmail("dave@gmail.com"))
                .thenReturn(Optional.of(user(5, "dave@gmail.com")));
        Fixture unknown = fixture();
        when(unknown.repo().findByVerifiedEmail(anyString())).thenReturn(Optional.empty());

        known.svc().sendSigninCode("dave@gmail.com");
        unknown.svc().sendSigninCode("stranger@gmail.com");
        assertEquals(known.gw().sent.size(), unknown.gw().sent.size(), "发信与否不能有差别");

        // 错码在两边给同一句话
        for (Fixture f : List.of(known, unknown)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> f.svc().verifySigninCode("whoever@gmail.com", "123456"));
            assertEquals("验证码错误或已过期", e.getMessage());
        }
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
