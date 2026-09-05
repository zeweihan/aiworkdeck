package com.checkba.config;

import com.checkba.exception.UnauthorizedException;
import com.checkba.version.VersionController;
import com.checkba.version.VersionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * PR4-0 未登录判定去中文化：前端 api.js 只认 code=4010 清会话/跳登录，
 * 不再做「登录/未授权/请先」中文子串匹配。这里钉住三条契约：
 * 1) UnauthorizedException 与字面量「未登录」「请先登录」的 IllegalArgumentException → 4010；
 * 2) 其余 IllegalArgumentException（包括含「请先」的业务提示）仍是 code=1——精确匹配不做子串；
 * 3) VersionException.userFacing 含「请先」的业务文案绝不带 4010（旧子串判定曾把
 *    版本/云同步提示误判成未登录，把用户踢回登录页）。
 */
class GlobalExceptionHandlerAuthCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("UnauthorizedException → code=4010")
    void unauthorizedExceptionIs4010() {
        Map<String, Object> body = handler.handleUnauthorizedException(new UnauthorizedException()).getBody();
        assertEquals(GlobalExceptionHandler.CODE_UNAUTHENTICATED, body.get("code"));
        assertEquals("请先登录", body.get("message"));
    }

    @Test
    @DisplayName("IllegalArgumentException 字面量「未登录」「请先登录」→ 4010，其余仍是 code=1")
    void illegalArgumentLiteralWhitelist() {
        assertEquals(4010, handler.handleIllegalArgumentException(
                new IllegalArgumentException("未登录")).getBody().get("code"));
        assertEquals(4010, handler.handleIllegalArgumentException(
                new IllegalArgumentException("请先登录")).getBody().get("code"));
        // 只认精确字面量，不做子串：业务提示不许被判成未登录
        assertEquals(1, handler.handleIllegalArgumentException(
                new IllegalArgumentException("请先选择文件")).getBody().get("code"));
        assertEquals(1, handler.handleIllegalArgumentException(
                new IllegalArgumentException("邮箱登录未启用")).getBody().get("code"));
        assertEquals(1, handler.handleIllegalArgumentException(
                new IllegalArgumentException()).getBody().get("code"));
    }

    @Test
    @DisplayName("VersionException.userFacing 含「请先」的业务文案不带 4010")
    void versionUserFacingMessageNever4010() {
        VersionController controller = new VersionController(null, null, null, null, null,
                mock(com.checkba.service.telemetry.TelemetryService.class), null);
        Map<String, Object> body = controller.onVersionError(
                VersionException.userFacing("请先结束当前工作段")).getBody();
        assertEquals(1, body.get("code"));
        assertEquals("请先结束当前工作段", body.get("message"));
    }
}
