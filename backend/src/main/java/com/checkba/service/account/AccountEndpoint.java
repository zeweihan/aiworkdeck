package com.checkba.service.account;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 官网账户服务地址（{@code ai.account.base-url}）的规范化与传输安全校验。
 *
 * <p>这条通道上跑的是明文 {@code awdk_} 账户 Key，所以默认只允许 https：配成明文 http
 * 等于把 Key 交给同网段的任何人。唯一的例外是**回环地址**——发往 127.0.0.1 / localhost / [::1]
 * 的流量不出本机网卡，「同网段窃听」这个威胁本身不成立，这也是浏览器把 localhost
 * 当作 secure context 的同一条理由。官网仓本地 {@code npm run dev} 联调必须走这条口子，
 * 否则桌面端与官网只能拿生产环境对，改契约就没有安全的验证场地。
 *
 * <p>其余任何 http 地址一律在启动期拒绝（属明确的错误配置，不做降级）。
 */
public final class AccountEndpoint {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "::1", "[::1]");

    /** 127.0.0.0/8 的点分四段字面量。**不能**用 startsWith("127.") 判断——那会放行 127.0.0.1.evil.com。 */
    private static final java.util.regex.Pattern IPV4_LOOPBACK =
            java.util.regex.Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private AccountEndpoint() {
    }

    /**
     * 去掉尾部斜杠并校验协议。
     *
     * @throws IllegalArgumentException 非 https 且非回环 http
     */
    public static String requireSecure(String baseUrl) {
        String url = baseUrl == null ? null : (baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl);
        if (url != null) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("https://")) {
                return url;
            }
            if (lower.startsWith("http://") && isLoopback(url)) {
                return url;
            }
        }
        throw new IllegalArgumentException("ai.account.base-url 必须是 https 地址（当前：" + baseUrl
                + "）。账户 Key 是明文凭据，不允许走未加密通道；本地联调可用 http://localhost。");
    }

    private static boolean isLoopback(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return LOOPBACK_HOSTS.contains(normalized) || IPV4_LOOPBACK.matcher(normalized).matches();
        } catch (Exception e) {
            return false;
        }
    }
}
