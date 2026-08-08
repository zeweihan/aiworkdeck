package com.checkba.service.mail;

/**
 * 邮件发信通道。按**收件域名**分流：国内主流邮箱走阿里云邮件推送
 * （{@link DomesticMailGateway}），其余走 Resend（{@link GlobalMailGateway}）。
 *
 * <p>分流不是优化而是刚需：QQ/163/126 对境外 IP 发来的信过滤很凶，验证码进垃圾箱
 * 甚至被拒收都很常见；反过来 Gmail 收阿里云的信到达率也一般。用错通道的后果是
 * 用户收不到码 = 登不上，所以宁可多一条通道也不合并。
 *
 * <p>与 {@code SmsGateway} 同构（那边按号码归属地分流），两条路各自独立开关：
 * 只配了一条的部署照常工作，{@link MailRouter} 会把不属于它的收件人也兜给它。
 */
public interface MailGateway {

    /** 通道名，仅用于日志与诊断。 */
    String name();

    /** 配置齐全且开关打开。 */
    boolean enabled();

    /** 该通道是否负责这个收件地址（入参是 {@link MailRouter#normalize} 规范化后的地址）。 */
    boolean supports(String email);

    /** 发送纯文本邮件；失败抛 {@link IllegalArgumentException}（文案不得含「登录/未授权/请先」）。 */
    void send(String to, String subject, String text);
}
