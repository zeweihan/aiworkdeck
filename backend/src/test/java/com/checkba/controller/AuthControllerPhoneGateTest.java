package com.checkba.controller;

import com.checkba.config.PhoneLoginGuard;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import com.checkba.service.UserSessionService;
import com.checkba.service.auth.VerificationCodeStore;
import com.checkba.service.mail.MailAuthService;
import com.checkba.service.sms.SmsAuthService;
import com.checkba.service.sms.SmsService;
import com.checkba.service.sms.SmsTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 手机号补绑闸接进登录链路（spec §5）。
 *
 * 这里钉的是**接线**，不是策略语义（那在 PhoneBindingGateTest）。三条不变式：
 * 期限内未绑号照常登录但带 mustBindPhone 信号；期限后未绑号拒登且**一个凭据都不签发**；
 * 拒登码不能是 4010——那是前端 api.js 认定的「会话失效」专用码，回它会让客户端清掉会话。
 */
class AuthControllerPhoneGateTest {

    private static final SmsTransport OK_TRANSPORT =
            (url, body, auth) -> new SmsTransport.Reply(200, "{\"Code\":\"OK\"}");

    /** 补绑期内 / 期限后：靠配进 guard 的期限值切换，不依赖机器当前日期。 */
    private static final String LONG_PAST = "2000-01-01";
    private static final String FAR_FUTURE = "2099-12-31";

    private static MockHttpServletRequest http() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("9.9.9.9");
        return request;
    }

    private static User user(String phone) {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setRole("USER");
        user.setSubscriptionType("FREE");
        user.setPhone(phone);
        return user;
    }

    private static PhoneLoginGuard guard(boolean required, String deadline) {
        SmsAuthService sms = new SmsAuthService(
                List.of(new SmsService(OK_TRANSPORT, true, "ak", "sk", "sign", "tpl")),
                new VerificationCodeStore(), mock(UserRepository.class), false);
        return new PhoneLoginGuard(sms, required, false, deadline);
    }

    private static AuthController controller(UserService userService, UserSessionService sessions,
                                             PhoneLoginGuard phoneLoginGuard) {
        return controller(userService, sessions, phoneLoginGuard, null, null);
    }

    private static AuthController controller(UserService userService, UserSessionService sessions,
                                             PhoneLoginGuard phoneLoginGuard,
                                             DeviceTokenService deviceTokenService,
                                             MailAuthService mailAuthService) {
        return new AuthController(userService, null, null, deviceTokenService,
                mock(AuthAbuseGuard.class), null, null, mailAuthService, null, sessions,
                false, phoneLoginGuard);
    }

    private static UserSessionService sessions() {
        UserSessionService sessions = mock(UserSessionService.class);
        when(sessions.issue(anyLong())).thenReturn("session_stub");
        return sessions;
    }

    private static AuthController.LoginRequest loginRequest() {
        AuthController.LoginRequest r = new AuthController.LoginRequest();
        r.setUsername("alice");
        r.setPassword("pw123456");
        return r;
    }

    private static UserService loginReturning(User user) {
        UserService userService = mock(UserService.class);
        when(userService.login(anyString(), anyString())).thenReturn(user);
        return userService;
    }

    // ==================== /login ====================

    @Test
    @DisplayName("期限内未绑号：照常登录、照常发会话，但要带 mustBindPhone 让客户端立刻弹强制补绑")
    void beforeDeadlineUnboundSignsInWithMustBindSignal() {
        UserSessionService sessions = sessions();
        Map<String, Object> result = controller(loginReturning(user(null)), sessions, guard(true, FAR_FUTURE))
                .login(loginRequest(), http());

        assertEquals(0, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals("session_stub", data.get("sessionId"));
        assertEquals(true, data.get("mustBindPhone"), "期限内未绑号必须带补绑信号，否则客户端无从知道要弹窗");
        verify(sessions).issue(7L);
    }

    @Test
    @DisplayName("期限后未绑号：拒登，且**一个会话都不许签发**——先建会话再拒等于给了个能用的凭据")
    void afterDeadlineUnboundIsRefusedBeforeAnySessionIsIssued() {
        UserSessionService sessions = sessions();
        Map<String, Object> result = controller(loginReturning(user(null)), sessions, guard(true, LONG_PAST))
                .login(loginRequest(), http());

        assertNotEquals(0, result.get("code"));
        assertNull(result.get("data"), "拒登不得带回任何 data，更不许夹带 sessionId");
        verify(sessions, never()).issue(anyLong());
    }

    @Test
    @DisplayName("拒登码不能是 4010——那是「会话失效」专用码，回它会让前端把会话清掉")
    void refusalCodeIsNotTheSessionExpiredCode() {
        Map<String, Object> result = controller(loginReturning(user(null)), sessions(), guard(true, LONG_PAST))
                .login(loginRequest(), http());

        assertNotEquals(4010, result.get("code"));
        assertEquals(AuthController.CODE_PHONE_BINDING_REQUIRED, result.get("code"));
    }

    @Test
    @DisplayName("被锁在门外的用户看得到人工通道，否则他就是真的进不来了")
    void refusalPointsAtTheSupportMailbox() {
        Map<String, Object> result = controller(loginReturning(user(null)), sessions(), guard(true, LONG_PAST))
                .login(loginRequest(), http());

        assertTrue(String.valueOf(result.get("message")).contains("hi@aiworkdeck.com"),
                "拒登文案必须指向 hi@aiworkdeck.com：实际是 " + result.get("message"));
    }

    @Test
    @DisplayName("已绑号不受影响：期限过了也照常登录，且不带补绑信号")
    void boundUserIsUnaffected() {
        UserSessionService sessions = sessions();
        Map<String, Object> result = controller(loginReturning(user("13800000000")), sessions, guard(true, LONG_PAST))
                .login(loginRequest(), http());

        assertEquals(0, result.get("code"));
        assertEquals(false, ((Map<?, ?>) result.get("data")).get("mustBindPhone"));
        verify(sessions).issue(7L);
    }

    @Test
    @DisplayName("没开强制时未绑号照常登录——这条闸默认对团队服务器/私有部署不生效")
    void notRequiredMeansNoGate() {
        UserSessionService sessions = sessions();
        Map<String, Object> result = controller(loginReturning(user(null)), sessions, guard(false, LONG_PAST))
                .login(loginRequest(), http());

        assertEquals(0, result.get("code"));
        assertEquals(false, ((Map<?, ?>) result.get("data")).get("mustBindPhone"));
        verify(sessions).issue(7L);
    }

    // ==================== /device-token ====================

    @Test
    @DisplayName("换设备令牌也是一次密码登录：期限后未绑号必须同样拒，否则换个端点就绕过去了")
    void deviceTokenPathIsGatedToo() {
        DeviceTokenService tokens = mock(DeviceTokenService.class);
        when(tokens.issue(anyLong(), any())).thenReturn(new DeviceTokenService.IssuedToken(1L, "awdt_x"));

        Map<String, Object> result = controller(loginReturning(user(null)), sessions(),
                guard(true, LONG_PAST), tokens, null)
                .issueDeviceToken(Map.of("username", "alice", "password", "pw123456"), http());

        assertEquals(AuthController.CODE_PHONE_BINDING_REQUIRED, result.get("code"));
        verify(tokens, never()).issue(anyLong(), any());
    }

    @Test
    @DisplayName("期限内未绑号仍能换令牌，同样带补绑信号")
    void deviceTokenBeforeDeadlineCarriesMustBind() {
        DeviceTokenService tokens = mock(DeviceTokenService.class);
        when(tokens.issue(anyLong(), any())).thenReturn(new DeviceTokenService.IssuedToken(1L, "awdt_x"));

        Map<String, Object> result = controller(loginReturning(user(null)), sessions(),
                guard(true, FAR_FUTURE), tokens, null)
                .issueDeviceToken(Map.of("username", "alice", "password", "pw123456"), http());

        assertEquals(0, result.get("code"));
        assertEquals(true, ((Map<?, ?>) result.get("data")).get("mustBindPhone"));
    }

    // ==================== /mail-login/verify ====================

    @Test
    @DisplayName("邮箱免密登录同样过闸——留着它等于给未绑号用户开了条并行入口")
    void mailLoginPathIsGatedToo() {
        MailAuthService mail = mock(MailAuthService.class);
        when(mail.verifySigninCode(anyString(), anyString())).thenReturn(user(null));
        UserSessionService sessions = sessions();

        AuthController.MailLoginRequest r = new AuthController.MailLoginRequest();
        r.setEmail("alice@gmail.com");
        r.setCode("123456");

        Map<String, Object> result = controller(mock(UserService.class), sessions,
                guard(true, LONG_PAST), null, mail).mailLoginVerify(r, http());

        assertEquals(AuthController.CODE_PHONE_BINDING_REQUIRED, result.get("code"));
        verify(sessions, never()).issue(anyLong());
    }

    // ==================== awdk 桥不受影响 ====================

    @Test
    @DisplayName("guard 为 null（未接线的调用方）时一律放行，不把既有链路连坐")
    void nullGuardIsInert() {
        UserSessionService sessions = sessions();
        Map<String, Object> result = controller(loginReturning(user(null)), sessions, null)
                .login(loginRequest(), http());

        assertEquals(0, result.get("code"));
        verify(sessions).issue(7L);
    }
}
