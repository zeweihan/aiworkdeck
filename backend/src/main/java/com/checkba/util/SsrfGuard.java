package com.checkba.util;

import java.net.InetAddress;
import java.net.URI;

/**
 * 出站请求的目标校验，防 SSRF。
 *
 * 用在「URL 由不可信方给出」的出站抓取上：AI 工具的 browse_url 由模型填 URL，
 * 而模型读的文档正文、网页内容都可能带注入指令；浏览器面板/预览类接口的 URL 同理。
 * 服务端发起的请求天然带着内网身份，能打到 127.0.0.1 上的管理端口、
 * 内网服务，以及云厂商的 169.254.169.254 元数据接口（可换取实例凭证）。
 *
 * 校验按解析后的 IP 判断而非字符串匹配：单看主机名挡不住 127.0.0.1 的各种写法
 * （0x7f.1、十进制整数、指向内网的公网域名等）。调用方还必须在跳转时重新校验，
 * 否则一个 302 就绕过了首次检查。
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    /** 目标不允许访问时返回原因；允许则返回 null。 */
    public static String rejectIfBlocked(String url) {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return "Error: malformed URL.";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "Error: only http/https URLs are allowed.";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "Error: URL has no host.";
        }
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "Error: cannot resolve host '" + host + "'.";
        }
        // 任何一个解析结果落在内网就整体拒绝：多 A 记录里混一条 127.0.0.1 是常见绕过手法
        for (InetAddress addr : resolved) {
            if (isBlocked(addr)) {
                return "Error: refusing to fetch internal or private address (host '" + host + "').";
            }
        }
        return null;
    }

    private static boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isLinkLocalAddress()  // 169.254.0.0/16（含云元数据 169.254.169.254）, fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 16) {
            // IPv6 唯一本地地址 fc00::/7，Java 的 isSiteLocalAddress 不覆盖
            return (b[0] & 0xFE) == 0xFC;
        }
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT，部分云厂商在此段放内部服务
            if (first == 100 && second >= 64 && second <= 127) return true;
            // 192.0.0.0/24 IETF 协议专用
            if (first == 192 && second == 0 && (b[2] & 0xFF) == 0) return true;
        }
        return false;
    }
}
