package com.checkba.service.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 国内邮箱通道：阿里云邮件推送（DirectMail），发信域名 {@code dm.aiworkdeck.com}。
 *
 * <p>只认下面这份主流国内邮箱域名清单。国内用户用企业自建域名（腾讯企业邮 / 阿里企业邮
 * 托管的公司域名）时不会命中，会落到 {@link GlobalMailGateway}——那是有意的取舍：
 * 域名归属地无法从字符串判断，与其猜错不如让兜底通道发，Resend 发企业域名并不差。
 *
 * <p>{@code @Order} 决定 {@link MailRouter} 的匹配顺序：本通道必须排在兜底通道之前。
 */
@Service
@Order(10)
public class DomesticMailGateway extends SmtpMailGateway {

    /** 主流国内邮箱域名（含各家的会员域名别名）。 */
    static final Set<String> DOMAINS = Set.of(
            "qq.com", "vip.qq.com", "foxmail.com",
            "163.com", "126.com", "yeah.net", "188.com", "vip.163.com", "vip.126.com",
            "sina.com", "sina.cn", "vip.sina.com", "sohu.com",
            "139.com", "189.cn", "wo.cn",
            "aliyun.com", "tom.com", "21cn.com", "263.net", "china.com"
    );

    @Autowired
    public DomesticMailGateway(
            @Value("${mail.domestic.enabled:false}") boolean enabled,
            @Value("${mail.domestic.host:smtpdm.aliyun.com}") String host,
            @Value("${mail.domestic.port:465}") int port,
            @Value("${mail.domestic.username:}") String username,
            @Value("${mail.domestic.password:}") String password,
            @Value("${mail.domestic.from:}") String from,
            @Value("${mail.from-name:AI WorkDeck}") String fromName) {
        super(enabled, host, port, username, password, from, fromName);
    }

    @Override
    public String name() {
        return "aliyun-directmail";
    }

    @Override
    public boolean supports(String email) {
        return email != null && DOMAINS.contains(MailRouter.domainOf(email));
    }
}
