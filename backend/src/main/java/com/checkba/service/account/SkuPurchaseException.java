package com.checkba.service.account;

/**
 * 应用内 SKU 购买（POST /api/account/purchase 转发）的业务失败。
 *
 * 在 {@link AccountException} 之外多带一个机器可读的 {@code reason}
 * （{@code already_owned} / {@code insufficient_credits} / {@code invalid_sku}）：
 * 「余额不足」在前端要多摆一个「去充值」按钮，靠 message 子串判断会随双语文案漂移，
 * 所以把官网返回的 error code 原样带上来，由 AccountController 的专用
 * ExceptionHandler 放进 code=1 信封的 {@code reason} 字段。
 * message 红线同父类：不含「登录」「未授权」「请先」，绝不被 api.js 误判成掉线。
 */
public class SkuPurchaseException extends AccountException {

    /** 官网返回的 error code；未知失败时可为 null。 */
    private final String reason;

    public SkuPurchaseException(Kind kind, String reason, String message) {
        super(kind, message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
