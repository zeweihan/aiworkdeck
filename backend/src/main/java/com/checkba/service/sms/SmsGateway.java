package com.checkba.service.sms;

/**
 * 短信通道。按号码归属地分流：大陆走阿里云（{@link SmsService}），
 * 境外走国际通道（{@link TwilioSmsGateway}）。两条路各自独立开关，互不影响——
 * 只配了国内的部署照常只服务大陆号，境外号会被判为「暂不支持」而不是发失败。
 */
public interface SmsGateway {

    /** 配置齐全且开关打开。 */
    boolean enabled();

    /** 该通道是否负责这个号码（入参是 {@code SmsAuthService} 规范化后的号码）。 */
    boolean supports(String phone);

    /** 发送验证码；失败抛 {@link IllegalArgumentException}（文案不得含「登录/未授权/请先」）。 */
    void sendVerificationCode(String phone, String code);
}
