// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.config.AppLanguageRequestFilter;
import com.checkba.config.GlobalExceptionHandler;
import com.checkba.config.ReviewAccountGate;
import com.checkba.controller.AuthController;
import com.checkba.exception.UnauthorizedException;
import com.checkba.repository.UserRepository;
import com.checkba.repository.UserSessionRepository;
import com.checkba.service.auth.VerificationCodeStore;
import com.checkba.service.mail.MailAuthService;
import com.checkba.service.mail.MailGateway;
import com.checkba.service.mail.MailRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 手机端接口的用户可见报错跟随 {@code X-App-Language}（dev-board#843，背景 #837：
 * 国际版账号的请求带 {@code X-App-Language: en-US}）。
 *
 * <p>第一个用例是原始复现：邮箱免密登录发码、邮箱格式不对，经真实的
 * {@link AppLanguageRequestFilter} 走完整条请求链，英文头必须回英文、不带头仍是中文。
 * 其余用例钉住登录限频/锁定、验证码冷却与未登录出口这几处共享文案。
 */
class MobileApiLanguageTest {

    @BeforeEach
    void registerLanguageService() {
        // 真实 AppLanguageService：全局设置为空 = 中文，只有请求级作用域能把它翻成英文
        LangText.register(new AppLanguageService(mock(SystemSettingService.class)));
    }

    @AfterEach
    void resetLangText() {
        LangText.reset();
    }

    private static final class NoopGateway implements MailGateway {
        @Override public String name() { return "noop"; }
        @Override public boolean enabled() { return true; }
        @Override public boolean supports(String email) { return true; }
        @Override public void send(String to, String subject, String text) { }
    }

    private static MockMvc mailLoginMvc() {
        MailAuthService mail = new MailAuthService(new VerificationCodeStore(),
                new MailRouter(List.of(new NoopGateway())), mock(UserRepository.class), false, true,
                ReviewAccountGate.disabled(), mock(UserService.class));
        AuthController controller = new AuthController(mock(UserService.class), null, null, null,
                new AuthAbuseGuard(false, "open", System::currentTimeMillis), null, null, mail, null,
                new UserSessionService(mock(UserSessionRepository.class), 365), false, null,
                mock(com.checkba.service.account.AccountDeletionService.class), null);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AppLanguageRequestFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("mail-login/send-code 邮箱格式不对：en-US 头回英文，不带头仍是中文")
    void mailLoginInvalidEmailFollowsHeader() throws Exception {
        MockMvc mvc = mailLoginMvc();
        mvc.perform(post("/api/auth/mail-login/send-code")
                        .header(AppLanguageRequestFilter.HEADER, "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("Invalid email address"));
        mvc.perform(post("/api/auth/mail-login/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("邮箱格式不正确"));
    }

    @Test
    @DisplayName("account/delete 未登录：en-US 头回英文，code 仍是 4010")
    void accountDeleteUnauthenticatedFollowsHeader() throws Exception {
        mailLoginMvc().perform(post("/api/auth/account/delete")
                        .header(AppLanguageRequestFilter.HEADER, "en-US"))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED))
                .andExpect(jsonPath("$.message").value("Not signed in"));
    }

    @Test
    @DisplayName("登录锁定与发码限频（IP 维度）：英文作用域下回英文")
    void abuseGuardMessagesInEnglish() {
        AtomicLong now = new AtomicLong(1_000_000L);
        AuthAbuseGuard guard = new AuthAbuseGuard(false, "open", now::get);
        for (int i = 0; i < AuthAbuseGuard.MAX_LOGIN_FAILURES; i++) {
            guard.recordLoginFailure("1.1.1.1", "alice@example.com");
        }
        for (int i = 0; i < AuthAbuseGuard.MAX_CODE_SENDS_PER_WINDOW; i++) {
            guard.recordCodeSend("1.1.1.1");
        }
        AppLanguageScope.run("en-US", () -> {
            assertEquals("Too many attempts. This account is temporarily locked and will unlock "
                            + "automatically in " + AuthAbuseGuard.LOCKOUT.toMinutes() + " minutes",
                    assertThrows(IllegalArgumentException.class,
                            () -> guard.checkLoginAttempt("1.1.1.1", "alice@example.com")).getMessage());
            assertEquals("Verification codes are being requested too often, please try again later",
                    assertThrows(IllegalArgumentException.class,
                            () -> guard.checkCodeSendRate("1.1.1.1")).getMessage());
        });
        // 不带头：逐字不变
        assertEquals("验证码发送过于频繁，请稍后再试", assertThrows(IllegalArgumentException.class,
                () -> guard.checkCodeSendRate("1.1.1.1")).getMessage());
    }

    @Test
    @DisplayName("同一邮箱/手机号冷却期内重发：英文作用域下回英文")
    void codeStoreCooldownInEnglish() {
        VerificationCodeStore store = new VerificationCodeStore();
        store.issue("mail-login", "bob@example.com");
        AppLanguageScope.run("en-US", () -> assertEquals(
                "Verification codes are being requested too often, please try again later",
                assertThrows(IllegalArgumentException.class,
                        () -> store.issue("mail-login", "bob@example.com")).getMessage()));
    }

    @Test
    @DisplayName("全局出口：未登录两个字面量与兜底文案按请求语言翻译，4010 判定不受影响")
    void exceptionHandlerLocalizesAuthAndFallbacks() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        AppLanguageScope.run("en-US", () -> {
            Map<String, Object> unauthorized = handler
                    .handleUnauthorizedException(new UnauthorizedException("请先登录")).getBody();
            assertEquals(GlobalExceptionHandler.CODE_UNAUTHENTICATED, unauthorized.get("code"));
            assertEquals("Please sign in first", unauthorized.get("message"));

            Map<String, Object> literal = handler
                    .handleIllegalArgumentException(new IllegalArgumentException("未登录")).getBody();
            assertEquals(GlobalExceptionHandler.CODE_UNAUTHENTICATED, literal.get("code"));
            assertEquals("Not signed in", literal.get("message"));

            assertEquals("Internal server error",
                    handler.handleException(new RuntimeException("boom")).getBody().get("message"));
        });
        assertEquals("请先登录", handler.handleUnauthorizedException(new UnauthorizedException())
                .getBody().get("message"));
    }
}
