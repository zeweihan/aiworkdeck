package com.checkba.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * getUsernameFromSession 吞异常——仅补日志、不改行为的护栏。
 *
 * <p>病灶：{@code userService.getUserById(userId)} 抛出的任何瞬时异常（DB 连接抖动、
 * 懒加载失败……）被 {@code catch (Exception e) { return null; }} 原样吞掉，与「用户真的
 * 不存在」返回同一个 null，调用方（署名归属等）区分不出「这次查询失败」和「查无此人」。
 * #498 复核时判定为「刻意没做的」——正确修法只是补一条日志，不改变任何用户可见行为，
 * 这里只做这一步：仍然返回 null，但留一条日志。
 */
class AuthControllerGetUsernameLoggingTest {

    private Object previousLocalIdentity;

    private static Field localIdentityField() throws Exception {
        Field field = AuthController.class.getDeclaredField("staticLocalIdentityService");
        field.setAccessible(true);
        return field;
    }

    /** 静态注册位是全局状态，用完必须还原，否则会污染同一 JVM 里的其它测试。 */
    @BeforeEach
    void rememberLocalIdentity() throws Exception {
        previousLocalIdentity = localIdentityField().get(null);
    }

    @AfterEach
    void restoreLocalIdentity() throws Exception {
        localIdentityField().set(null, previousLocalIdentity);
    }

    @Test
    @DisplayName("userService.getUserById 抛异常时记一条日志，但仍然返回 null（行为不变）")
    void logsExceptionButStillReturnsNull() throws Exception {
        UserService userService = mock(UserService.class);
        when(userService.getUserById(anyLong())).thenThrow(new RuntimeException("模拟 DB 抖动"));

        LocalIdentityService localIdentity = mock(LocalIdentityService.class);
        when(localIdentity.isLocalMode()).thenReturn(true);
        when(localIdentity.localUserId()).thenReturn(7L);
        AuthController.registerLocalIdentityService(localIdentity);

        com.checkba.service.UserSessionService sessions = new com.checkba.service.UserSessionService(
                mock(com.checkba.repository.UserSessionRepository.class), 365);
        // 构造即注册 staticUserService（构造器里的既有副作用，其它测试同样依赖这个模式）
        new AuthController(userService, null, null, null, null, null, null, null, null,
                sessions, true, null,
                mock(com.checkba.service.account.AccountDeletionService.class));

        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AuthController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        String result;
        try {
            // sessionId=null + local-mode：直接走 localIdentity.localUserId()，
            // 不需要再搭一整套 session 解析就能命中 getUserById 抛异常这条路径。
            result = AuthController.getUsernameFromSession(null);
        } finally {
            logbackLogger.detachAppender(appender);
        }

        assertNull(result, "行为不能变——吞异常返回 null 这件事本身不动");

        boolean logged = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR);
        assertTrue(logged, "getUserById 抛异常时应该留一条日志，此前完全静默。实际日志：" +
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining(" | ")));
    }
}
