package com.checkba.controller;

import com.checkba.service.LangText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 简易网页代理：
 * - 解决 iframe 跨域无法拦截 target=_blank/window.open 的问题
 * - 我们把页面 HTML 拉回后注入脚本，在 iframe 内捕获“新开标签”并 postMessage 给父窗口，让父窗口在工作区新建标签页
 *
 * 注意：这是“最小可用”实现，不保证兼容所有站点（复杂 CSP/分片加载/反爬会失败）。
 */
@RestController
@RequestMapping("/api/browser")
public class BrowserProxyController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrowserProxyController.class);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            // NEVER：不自动跟随重定向，改为手动逐跳做 SSRF 校验，防止允许的外站 302 到内网/元数据端点
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * e2e 专用的 SSRF 例外名单（逗号分隔的主机名，精确匹配、不认通配）。
     *
     * <p>存在的唯一理由：app-e2e 要在本机起一个两页小站来验这条代理链路，而 {@link
     * com.checkba.util.SsrfGuard} 按解析后的 IP 判断，127.0.0.1 与任何能在本机 bind 的地址
     * （回环、RFC1918、CGNAT）全在拦截清单里——不开这个口子，浏览器面板这条链路就只能拿外网
     * 站点当断言对象，等于把测试挂在网络可达性上。
     *
     * <p><b>默认空，且刻意不写进任何 application*.yml</b>：只有显式传
     * {@code SECURITY_BROWSER_PROXY_E2E_ALLOWED_HOSTS} 的进程才会放行，发行版永远拿不到。
     * 放行的是「服务端替你去抓这个地址」，本机后端在 local-mode 下把每个请求都当本机用户，
     * 放行内网地址等于把本机的管理端口暴露给被访问的网页——所以这里只认精确主机名，
     * 也绝不要为了省事填成通配。{@code BrowserProxyControllerTest} 钉住「不设 = 照样拦」。
     */
    @org.springframework.beans.factory.annotation.Value("${security.browser-proxy.e2e-allowed-hosts:}")
    private String e2eAllowedHosts;

    /**
     * 单次代理允许拉回的最大字节数。默认 50MB。
     *
     * <p>注入的点击拦截脚本把 iframe 里**所有**同标签页链接点击都改道这个端点，
     * 其中包括普通的大文件下载链接。上游响应此前是 {@code ofByteArray()} 全量进堆，
     * 没有任何体积闸——后端是所有用户共用的一个 JVM，一次几百 MB 的下载就能把堆顶起来。
     */
    @org.springframework.beans.factory.annotation.Value("${security.browser-proxy.max-bytes:52428800}")
    private long maxBytes = 52428800L;

    /**
     * 一次代理请求（含全部重定向跳）的总时限，秒。
     *
     * <p>此前只有每跳 20 秒的单跳超时，5 跳串起来能让一个请求占住一条 Tomcat 工作线程
     * 将近 150 秒。慢速目标站（或一串各自慢一点的重定向）几个并发就能把线程池吃干净，
     * 整个后端跟着卡住——而调用方只是在网页面板里点了个链接。
     */
    @org.springframework.beans.factory.annotation.Value("${security.browser-proxy.deadline-seconds:30}")
    private long deadlineSeconds = 30L;

    /** 主机是否在 e2e 例外名单里（精确、忽略大小写）。名单为空时恒为 false。 */
    private boolean isE2eAllowedHost(String host) {
        if (host == null || host.isBlank() || e2eAllowedHosts == null || e2eAllowedHosts.isBlank()) return false;
        for (String allowed : e2eAllowedHosts.split(",")) {
            if (host.equalsIgnoreCase(allowed.trim())) return true;
        }
        return false;
    }

    @GetMapping("/proxy")
    public ResponseEntity<?> proxy(
            @RequestParam("url") String url,
            @RequestParam(value = "token", required = false) String token) {
        if (!StringUtils.hasText(url)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(LangText.of("url 不能为空", "url must not be empty"));
        }
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(LangText.of("仅支持 http/https", "Only http/https is supported"));
        }

        try {
            URI uri = URI.create(u);
            HttpResponse<java.io.InputStream> resp = null;
            long deadlineAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, deadlineSeconds));
            // 手动跟随重定向，每一跳都重新做 scheme 白名单 + SSRF 校验，避免自动跳转绕过 SsrfGuard
            for (int hop = 0; hop < 5; hop++) {
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(LangText.of("仅支持 http/https", "Only http/https is supported"));
                }
                // 网段清单统一由 SsrfGuard 维护：本控制器原先自己判断，漏了 100.64.0.0/10
                // （阿里云实例元数据 100.100.100.200 就在其中，能换取实例 RAM 凭证）与 IPv6 ULA
                // 例外名单默认空（见 e2eAllowedHosts），发行版走的永远是下面这条无条件校验
                if (!isE2eAllowedHost(uri.getHost())
                        && com.checkba.util.SsrfGuard.rejectIfBlocked(uri.toString()) != null) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(LangText.of("目标地址不被允许（已禁止本地/内网地址）", "Target address is not allowed (local/internal addresses are blocked)"));
                }
                Duration budget = Duration.ofNanos(deadlineAt - System.nanoTime());
                if (budget.isNegative() || budget.isZero()) {
                    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(LangText.of(
                            "代理超时：目标站点响应过慢", "Proxy timed out: the target site is too slow"));
                }
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .GET()
                        // 单跳超时取「剩余总预算」与 20 秒的较小值：跳数再多也不会累加成分钟级占用
                        .timeout(budget.compareTo(Duration.ofSeconds(20)) < 0 ? budget : Duration.ofSeconds(20))
                        .header("User-Agent", "checkba-browser/1.0")
                        .build();
                // 流式接收而不是 ofByteArray()：正文要不要收、收多少由下面的体积闸说了算
                resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    if (loc == null || loc.isBlank()) break;
                    closeQuietly(resp.body()); // 这一跳的正文用不上，别把连接挂在那儿
                    resp = null;
                    uri = uri.resolve(loc); // 支持相对 Location，下一轮重新校验
                    continue;
                }
                break;
            }
            if (resp == null) {
                // 跳满 5 次仍在重定向。此前这里会把最后那个 3xx 的正文当内容原样回给前端，
                // 换成明确报错更诚实。
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(LangText.of("代理失败: 重定向次数过多", "Proxy failed: too many redirects"));
            }

            long declared = resp.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declared > maxBytes) {
                closeQuietly(resp.body());
                return tooLarge();
            }
            byte[] payload;
            try (java.io.InputStream in = resp.body()) {
                payload = readBounded(in, maxBytes);
            }
            if (payload == null) {
                // 上游没声明长度（分块传输），读到超限就停手，堆里最多多出 maxBytes+1 字节
                return tooLarge();
            }

            String contentType = resp.headers().firstValue("content-type").orElse("application/octet-stream");
            // 只对 HTML 注入脚本，其它资源原样返回（图片/CSS/JS 等）
            if (contentType.toLowerCase().contains("text/html")) {
                // 按上游声明的字符集解码，回给浏览器时统一声明 UTF-8。
                // 以前是「按 UTF-8 解、回 text/html 不带 charset」：浏览器于是拿默认编码
                // （windows-1252）去解我们发出去的 UTF-8 字节，中文页面整页乱码。
                // 已知没覆盖的一档：只在 <meta charset> 里声明、响应头不带的 GBK 页面。
                String html = new String(payload, charsetOf(contentType));
                String injected = inject(html, uri.toString(), token);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
                headers.setCacheControl("no-store");
                return new ResponseEntity<>(injected, headers, HttpStatus.OK);
            }

            HttpHeaders headers = new HttpHeaders();
            try {
                headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            } catch (Exception ignore) {
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }
            headers.setCacheControl("no-store");
            return new ResponseEntity<>(payload, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("BrowserProxy failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(LangText.of("代理失败: ", "Proxy failed: ") + e.getMessage());
        }
    }

    private ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(LangText.of(
                "目标文件超过代理上限（" + (maxBytes / 1024 / 1024) + "MB），请在系统浏览器中打开",
                "The target exceeds the proxy limit (" + (maxBytes / 1024 / 1024) + "MB); open it in your system browser"));
    }

    /** 最多读 max 字节；超了立刻停手并返回 null（堆里最多多出 max+1 字节）。 */
    private static byte[] readBounded(java.io.InputStream in, long max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(java.io.InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignore) {
            // 关不掉就算了，连接池会自己回收
        }
    }

    /** 从响应头的 Content-Type 里取字符集；没写或者认不出就按 UTF-8。 */
    private java.nio.charset.Charset charsetOf(String contentType) {
        try {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    String name = p.substring("charset=".length()).trim().replace("\"", "");
                    if (java.nio.charset.Charset.isSupported(name)) return java.nio.charset.Charset.forName(name);
                }
            }
        } catch (Exception ignore) {
            // 落到 UTF-8
        }
        return StandardCharsets.UTF_8;
    }

    private String inject(String html, String baseUrl, String token) {
        String safeBase = escapeHtmlAttr(baseUrl);
        String safeToken = escapeJsString(token);
        // 页面的真实地址（跟完重定向之后那个）。注入进去是因为 iframe 自己的
        // location 是代理地址，页面无从知道“我到底在哪一页”，而渲染层要靠它更新标签地址。
        String safePageUrl = escapeJsString(baseUrl);

        // 1) base：让相对路径资源能回到原站点加载
        String baseTag = "<base href=\"" + safeBase + "\">";

        // 0) 尽量移除页面自带的 CSP meta（否则可能禁用我们注入的 inline script，导致“点了没反应”）
        String cleaned = stripCspMeta(html);

        // 2) 注入脚本：拦截 target=_blank / window.open，并把真实地址回报给渲染层
        //
        // 整段脚本拼成**一行**，所以里面绝对不能出现 `//` 行注释——它会把后面的
        // 全部代码一起注释掉，页面上只留一句 SyntaxError: Unexpected end of input，
        // 拦截、跳转、postMessage 三件事一起静默失效（这个 bug 存活到 2026-08 才发现，
        // 现象是“点了没反应”，很容易误判成 CSP 拦截）。要写注释只能用 /* */。
        String script = "<script>(function(){"
                + "var TOKEN='" + safeToken + "';"
                + "var PAGE_URL='" + safePageUrl + "';"
                + "function post(type,url){try{window.parent && window.parent.postMessage({__checkbaBrowser:true,token:TOKEN,type:type,url:url},'*');}catch(e){}}"
                + "function debug(msg){post('DEBUG',String(msg||''));}"
                /* proxify：必须拼成绝对地址。iframe 里有我们注入的 <base href=真站点>，
                   而 location.href 的赋值是按文档 base 解析的——给相对路径的话
                   '/api/browser/proxy?...' 会解析到被访问站点自己头上（实测跳出
                   http://被访站/api/browser/proxy?url=... 的四不像，站点回 404）。
                   拿当前文档地址（就是代理端点本身）换 query 最稳：不依赖后端知道
                   前端从哪个 origin/子路径来，也不怕应用部署在子路径下。 */
                + "function proxify(url){var q='?url='+encodeURIComponent(url)+'&token='+encodeURIComponent(TOKEN);"
                + "try{var u=new URL(location.href);u.search=q;return u.toString();}catch(e){return '/api/browser/proxy'+q;}}"
                /* 每次文档加载都回报一次真实地址：页内点链接、站点自己 302、SPA 换页
                   都会重新走一遍代理，渲染层据此让 tab.url 跟上。不报的话标签永远停在
                   打开时那个地址，切走再切回来就退回默认首页。 */
                + "post('URL_CHANGED',PAGE_URL);"
                + "var _open=window.open;"
                + "window.open=function(url){try{debug('window.open -> '+String(url||''));}catch(e){} if(url){post('OPEN_NEW_TAB',String(url));}return null;};"
                + "document.addEventListener('click',function(e){"
                + "var a=e.target;while(a && a.tagName!=='A'){a=a.parentElement;}"
                + "if(!a) return;"
                + "var href=a.getAttribute('href');"
                + "if(!href || href.startsWith('javascript:') || href.startsWith('#')) return;"
                + "var abs=a.href||href;"
                + "var t=(a.getAttribute('target')||'').toLowerCase();"
                + "if(t==='_blank'){try{debug('click _blank -> '+String(abs));}catch(e){} e.preventDefault();e.stopPropagation();post('OPEN_NEW_TAB',String(abs));return;}"
                /* 同一标签页内导航也强制走 proxy，保证后续页面仍可拦截 _blank/window.open */
                + "try{debug('click nav -> '+String(abs));}catch(e){}"
                + "e.preventDefault();e.stopPropagation();window.location.href=proxify(String(abs));"
                + "},true);"
                + "})();</script>";

        // 尽量注入到 <head> 开头；没有 head 就注入到 html 开头
        int headIdx = indexOfIgnoreCase(cleaned, "<head");
        if (headIdx >= 0) {
            int headEnd = cleaned.indexOf(">", headIdx);
            if (headEnd > headIdx) {
                return cleaned.substring(0, headEnd + 1) + baseTag + script + cleaned.substring(headEnd + 1);
            }
        }
        int htmlIdx = indexOfIgnoreCase(cleaned, "<html");
        if (htmlIdx >= 0) {
            int htmlEnd = cleaned.indexOf(">", htmlIdx);
            if (htmlEnd > htmlIdx) {
                return cleaned.substring(0, htmlEnd + 1) + "<head>" + baseTag + script + "</head>" + cleaned.substring(htmlEnd + 1);
            }
        }
        return baseTag + script + cleaned;
    }

    private int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    private String stripCspMeta(String html) {
        if (html == null || html.isBlank()) return html;
        // 移除常见 CSP meta（http-equiv / name）
        // 说明：这是 best-effort，避免出现 inline script 被禁止导致注入失效。
        String out = html;
        out = out.replaceAll("(?is)<meta\\s+[^>]*http-equiv\\s*=\\s*['\\\"]?content-security-policy['\\\"]?[^>]*>", "");
        out = out.replaceAll("(?is)<meta\\s+[^>]*name\\s*=\\s*['\\\"]?content-security-policy['\\\"]?[^>]*>", "");
        out = out.replaceAll("(?is)<meta\\s+[^>]*name\\s*=\\s*['\\\"]?csp['\\\"]?[^>]*>", "");
        return out;
    }

    /**
     * 转义成能安全塞进单引号 JS 字面量的串。
     *
     * <p>反斜杠必须先转义：只替换单引号的话，末尾带 {@code \} 的值会把我们补的转义符本身
     * 变成被转义的反斜杠，随后的单引号照样闭合字符串，等于在注入脚本里执行任意 JS。
     * {@code <} 一并转成 {@code \x3C}，否则值里带 {@code </script>} 就能直接闭合 script 标签
     * 跳出字面量——页面地址是被访问站点能控制的（302 到任意 URL），不是可信输入。
     */
    private String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("<", "\\x3C")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private String escapeHtmlAttr(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}


