package com.checkba.service.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 其余邮箱通道：Resend，发信域名 {@code send.aiworkdeck.com}。
 *
 * <p>{@link #supports} 恒为 true——它是兜底通道，接住所有没被 {@link DomesticMailGateway}
 * 认领的地址。因此 {@code @Order} 必须排在国内通道之后，否则会把 QQ/163 也吃掉。
 *
 * <p>Resend 的 SMTP 用户名固定是字面量 {@code resend}，密码填 API key——不是账号邮箱。
 */
@Service
@Order(20)
public class GlobalMailGateway extends SmtpMailGateway {

    @Autowired
    public GlobalMailGateway(
            @Value("${mail.global.enabled:false}") boolean enabled,
            @Value("${mail.global.host:smtp.resend.com}") String host,
            @Value("${mail.global.port:465}") int port,
            @Value("${mail.global.username:resend}") String username,
            @Value("${mail.global.password:}") String password,
            @Value("${mail.global.from:}") String from,
            @Value("${mail.from-name:AI Workdeck}") String fromName) {
        super(enabled, host, port, username, password, from, fromName);
    }

    @Override
    public String name() {
        return "resend";
    }

    /** 兜底通道：不认领具体域名，谁没人要就归它。 */
    @Override
    public boolean supports(String email) {
        return true;
    }
}
