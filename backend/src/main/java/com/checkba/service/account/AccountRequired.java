// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这个功能需要账户」的统一失败形状：业务码 <b>4011 {@code account_required}</b>
 * （登录后置设计 2026-09-29 §5.2，dev-board#1046）。
 *
 * <p>形状：HTTP 200，{@code {code:4011, kind:"NOT_CONNECTED", reason, message}}。
 * 前端 {@code services/api.js} 见到 4011 就地弹登录层，登录成功后由调用方决定要不要重试。
 *
 * <p><b>绝不用 4010</b>：4010 是「会话失效」，前端见到它会清掉本地会话（浏览器端还会跳登录页）。
 * 「这台电脑还没登录账户」不是会话失效，混用的话用户点一下 OCR 就被踢出去。
 *
 * <p>{@code reason} 说明是哪个功能要账户，前端据此换弹层顶部那一句说明；
 * 取值集合见下面的常量，与前端 {@code utils/requireAccount.js} 的 REASONS 对拍。
 * 缺省（null）时回包里不带这个字段，前端用通用说明。
 */
public final class AccountRequired {

    /** 业务码。与 4001（功能未配置）/ 4003（额度已满）/ 4005（缺二次验证码）/ 4010（会话失效）同族。 */
    public static final int CODE = 4011;

    public static final String REASON_PLATFORM_AI = "platform_ai";
    public static final String REASON_GATEWAY = "gateway";
    public static final String REASON_MARKET = "market";
    public static final String REASON_TEAM = "team";
    public static final String REASON_MOBILE = "mobile";
    public static final String REASON_MEETING = "meeting";
    public static final String REASON_DICTATION = "dictation";

    private AccountRequired() {
    }

    /** 抛出点统一用这个构造：kind 恒为 NOT_CONNECTED，reason 挂在异常上。 */
    public static AccountException exception(String reason, String message) {
        return new AccountException(AccountException.Kind.NOT_CONNECTED, message, reason);
    }

    /** 4011 信封本体。 */
    public static Map<String, Object> envelope(String reason, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", CODE);
        result.put("kind", AccountException.Kind.NOT_CONNECTED.name());
        if (reason != null && !reason.isBlank()) {
            result.put("reason", reason);
        }
        result.put("message", message);
        return result;
    }

    /**
     * 任意账户异常 → 信封：NOT_CONNECTED 走 4011，其余 kind 维持原来的
     * {@code {code:1, kind, message, reason?}}（与 {@code AccountController} 的本地处理同形）。
     */
    public static Map<String, Object> envelope(AccountException e) {
        if (e.getKind() == AccountException.Kind.NOT_CONNECTED) {
            return envelope(e.getReason(), e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 1);
        result.put("kind", e.getKind().name());
        result.put("message", e.getMessage());
        if (e.getReason() != null) {
            result.put("reason", e.getReason());
        }
        return result;
    }
}
