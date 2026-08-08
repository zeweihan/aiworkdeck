package com.checkba.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * 两条通道共用的 SMTP 发信实现——它们只在「凭据」和「负责哪些收件域名」上不同，
 * 发信动作完全一样，所以放在基类里，子类只声明身份。
 *
 * <p>每个网关自己持有一个 {@link JavaMailSenderImpl}，不共用 Spring Boot 的
 * {@code spring.mail.*} 单例：那套只能装配出一个 sender，而国内/国外必须并存。
 *
 * <p>用 MimeMessage 而不是 SimpleMailMessage，是因为要给发件人带显示名、
 * 并把主题按 UTF-8 编码——中文主题走 SimpleMailMessage 在部分接收方会乱码。
 */
@Slf4j
abstract class SmtpMailGateway implements MailGateway {

    /** SMTP 各阶段超时。发信在登录/通知的请求线程上，卡住的连接会拖垮调用方。 */
    private static final String TIMEOUT_MS = "10000";

    private final boolean enabled;
    private final String username;
    private final String password;
    private final String from;
    private final String fromName;
    private final JavaMailSenderImpl sender;

    SmtpMailGateway(boolean enabled, String host, int port, String username, String password,
                    String from, String fromName) {
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.from = StringUtils.hasText(from) ? from : username;
        this.fromName = fromName;

        // 构造不建立连接，纯属性对象，因此无需惰性化
        this.sender = new JavaMailSenderImpl();
        this.sender.setHost(host);
        this.sender.setPort(port);
        this.sender.setUsername(username);
        this.sender.setPassword(password);
        this.sender.setDefaultEncoding("UTF-8");

        Properties props = this.sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        // 465 是隐式 SSL，587 是先明文连上再 STARTTLS 升级；两者不能混用
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
    }

    /**
     * 发件地址必须是真地址：Resend 的 SMTP 用户名是字面量 {@code resend}，
     * 回落到 username 会拼出个非法 From，只有到发信那一刻才炸。宁可在这里判成未配置。
     */
    @Override
    public boolean enabled() {
        return enabled
                && StringUtils.hasText(sender.getHost())
                && StringUtils.hasText(username)
                && StringUtils.hasText(password)
                && from.contains("@");
    }

    @Override
    public void send(String to, String subject, String text) {
        if (!enabled()) {
            throw new IllegalArgumentException("邮件通道未配置");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
        } catch (Exception e) {
            // 服务商原始文案（含主机名、账号、限流细节）不外露给调用方
            log.warn("[mail] {} 发信失败 to={} err={}", name(), mask(to), e.toString());
            throw new IllegalArgumentException("邮件发送失败，请稍后重试");
        }
    }

    /** a***@example.com；日志里不留完整地址。 */
    static String mask(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at < 0 ? "" : email.substring(at));
        return email.charAt(0) + "***" + email.substring(at);
    }
}
