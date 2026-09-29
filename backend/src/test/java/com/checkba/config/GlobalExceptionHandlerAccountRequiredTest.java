// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountRequired;
import com.checkba.service.platform.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录后置（dev-board#1046，设计 2026-09-29 §5.2）：「需要账户」统一回 4011。
 *
 * <p>三条钉死：NOT_CONNECTED → 4011 + kind + reason；其余账户失败 kind 仍是 code=1；
 * 网关 NOT_CONNECTED 在原有 gatewayKind / canUseOwnKey 之上多一个 code=4011。
 * 以及那条老红线：<b>任何一种都绝不是 4010</b>——4010 会让前端清会话。
 */
class GlobalExceptionHandlerAccountRequiredTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("AccountException(NOT_CONNECTED) → 4011，kind 与 reason 原样带上")
    void notConnectedIs4011() {
        Map<String, Object> body = handler.handleAccountException(
                AccountRequired.exception(AccountRequired.REASON_MEETING, "平台转写需要连接 AI WorkDeck 账户")).getBody();
        assertEquals(4011, body.get("code"));
        assertEquals("NOT_CONNECTED", body.get("kind"));
        assertEquals("meeting", body.get("reason"));
        assertEquals("平台转写需要连接 AI WorkDeck 账户", body.get("message"));
    }

    @Test
    @DisplayName("NOT_CONNECTED 缺 reason 时回包里不带 reason 字段（前端用通用说明）")
    void notConnectedWithoutReason() {
        Map<String, Object> body = handler.handleAccountException(
                new AccountException(AccountException.Kind.NOT_CONNECTED, "尚未连接")).getBody();
        assertEquals(4011, body.get("code"));
        assertFalse(body.containsKey("reason"));
    }

    @Test
    @DisplayName("其余账户失败维持 code=1 + kind，不弹登录层")
    void otherKindsStayCodeOne() {
        for (AccountException.Kind kind : AccountException.Kind.values()) {
            if (kind == AccountException.Kind.NOT_CONNECTED) continue;
            Map<String, Object> body = handler.handleAccountException(
                    new AccountException(kind, "x", kind == AccountException.Kind.REJECTED ? "bad_code" : null)).getBody();
            assertEquals(1, body.get("code"), kind.name());
            assertEquals(kind.name(), body.get("kind"));
        }
    }

    @Test
    @DisplayName("网关 NOT_CONNECTED：code=4011 + reason=gateway，gatewayKind/canUseOwnKey 不动")
    void gatewayNotConnectedIs4011() {
        Map<String, Object> body = handler.handleGateway(
                new GatewayException(GatewayException.Kind.NOT_CONNECTED, "尚未连接")).getBody();
        assertEquals(4011, body.get("code"));
        assertEquals("NOT_CONNECTED", body.get("kind"));
        assertEquals("gateway", body.get("reason"));
        assertEquals("NOT_CONNECTED", body.get("gatewayKind"));
        assertEquals(Boolean.TRUE, body.get("canUseOwnKey"));
    }

    @Test
    @DisplayName("网关其余失败仍是 code=1，形状一字不改")
    void gatewayOtherKindsUnchanged() {
        Map<String, Object> body = handler.handleGateway(
                new GatewayException(GatewayException.Kind.NO_CREDITS, "余额不足")).getBody();
        assertEquals(1, body.get("code"));
        assertEquals("NO_CREDITS", body.get("gatewayKind"));
        assertFalse(body.containsKey("kind"));
    }

    @Test
    @DisplayName("红线：4011 与 4010 是两个码")
    void neverFourTen() {
        assertNotEquals(GlobalExceptionHandler.CODE_UNAUTHENTICATED, AccountRequired.CODE);
    }
}
