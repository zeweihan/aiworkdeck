package com.checkba.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 按收件域名把邮件分派到对应通道。所有发信都应经由这里，不要直接拿 {@link MailGateway}。
 *
 * <p>选路规则两步走：
 * <ol>
 *   <li>找**已启用且认领该域名**的通道（国内邮箱 → 阿里云，其余 → Resend）；</li>
 *   <li>没有则退回**任一已启用**的通道。</li>
 * </ol>
 * 第二步是有意的：只配了国内一条通道的部署，给 Gmail 发通知也得发得出去——
 * 用次优通道送达，好过因为「没人认领」而整条路不通。
 */
@Service
@Slf4j
public class MailRouter {

    /** 够用即可的地址形状校验：本地部分 + @ + 至少一级点分域名。 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private final List<MailGateway> gateways;

    @Autowired
    public MailRouter(List<MailGateway> gateways) {
        this.gateways = gateways;
    }

    /** 邮件功能在本部署形态下是否可用（任一通道配齐即算可用）。 */
    public boolean active() {
        return gateways.stream().anyMatch(MailGateway::enabled);
    }

    /** 选中负责该地址的通道。 */
    public MailGateway gatewayFor(String email) {
        List<MailGateway> on = gateways.stream().filter(MailGateway::enabled).toList();
        if (on.isEmpty()) {
            throw new IllegalArgumentException("邮件通道未配置");
        }
        return on.stream()
                .filter(g -> g.supports(email))
                .findFirst()
                .orElseGet(() -> on.get(0));
    }

    /** 发一封纯文本邮件（收件地址会先规范化）。 */
    public void send(String to, String subject, String text) {
        String normalized = normalize(to);
        MailGateway gateway = gatewayFor(normalized);
        gateway.send(normalized, subject, text);
        log.info("[mail] 已发送 to={} via={}", SmtpMailGateway.mask(normalized), gateway.name());
    }

    /**
     * 规范化到存储/比较形态：去空白、整体小写。
     *
     * <p>大小写敏感性按域名其实各家不同，但主流邮箱一律不区分；统一小写才能让
     * 「同一个邮箱」在唯一性检查里是同一条记录，否则 Foo@qq.com 和 foo@qq.com 会绑到两个账号。
     */
    public static String normalize(String email) {
        String trimmed = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return trimmed;
    }

    /** 取 @ 之后的域名部分；入参须已规范化。 */
    static String domainOf(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }
}
