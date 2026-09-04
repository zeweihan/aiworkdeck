package com.checkba.service.mobile;

/**
 * {@code /api/mobile/billing/*} 的业务失败，带 {@link MobileBillingKind} 判别位
 * （dev-board#425 复审 C2）。
 *
 * <p>{@code GlobalExceptionHandler} 有专门的 handler，压成
 * {@code 200 + {code:1, kind, outTradeNo?, message}}——{@code kind} 就是给客户端分支用的，
 * 客户端<b>不许</b>再去匹配 message 措辞。
 *
 * <p><b>为什么继承 {@link IllegalArgumentException}</b>：这一族错误原本就是以
 * IllegalArgumentException 的形式落到全局处理器的 {@code code:1} 分支，继承保证
 * 「万一 handler 漏注册」时行为退化成改造前的样子（少一个 kind，而不是变成 500）。
 * Spring 的 {@code ExceptionHandlerMethodResolver} 按继承距离选最具体的 handler，
 * 所以本类照样走自己那个。
 */
public class MobileBillingFailureException extends IllegalArgumentException {

    private final MobileBillingKind kind;

    /** 仅 {@link MobileBillingKind#ALREADY_PAID} / {@link MobileBillingKind#IDEMPOTENCY_CONFLICT} 有值。 */
    private final String outTradeNo;

    public MobileBillingFailureException(MobileBillingKind kind, String message) {
        this(kind, message, null);
    }

    public MobileBillingFailureException(MobileBillingKind kind, String message, String outTradeNo) {
        super(message);
        this.kind = kind;
        this.outTradeNo = outTradeNo;
    }

    public MobileBillingKind getKind() {
        return kind;
    }

    public String getOutTradeNo() {
        return outTradeNo;
    }
}
