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
            HttpResponse<byte[]> resp = null;
            // 手动跟随重定向，每一跳都重新做 scheme 白名单 + SSRF 校验，避免自动跳转绕过 SsrfGuard
            for (int hop = 0; hop < 5; hop++) {
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(LangText.of("仅支持 http/https", "Only http/https is supported"));
                }
                // 网段清单统一由 SsrfGuard 维护：本控制器原先自己判断，漏了 100.64.0.0/10
                // （阿里云实例元数据 100.100.100.200 就在其中，能换取实例 RAM 凭证）与 IPv6 ULA
                if (com.checkba.util.SsrfGuard.rejectIfBlocked(uri.toString()) != null) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(LangText.of("目标地址不被允许（已禁止本地/内网地址）", "Target address is not allowed (local/internal addresses are blocked)"));
                }
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "checkba-browser/1.0")
                        .build();
                resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    if (loc == null || loc.isBlank()) break;
                    uri = uri.resolve(loc); // 支持相对 Location，下一轮重新校验
                    continue;
                }
                break;
            }
            if (resp == null) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(LangText.of("代理失败: 无响应", "Proxy failed: no response"));
            }

            String contentType = resp.headers().firstValue("content-type").orElse("application/octet-stream");
            // 只对 HTML 注入脚本，其它资源原样返回（图片/CSS/JS 等）
            if (contentType.toLowerCase().contains("text/html")) {
                String html = new String(resp.body(), StandardCharsets.UTF_8);
                String injected = inject(html, uri.toString(), token);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.TEXT_HTML);
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
            return new ResponseEntity<>(resp.body(), headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("BrowserProxy failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(LangText.of("代理失败: ", "Proxy failed: ") + e.getMessage());
        }
    }

    private String inject(String html, String baseUrl, String token) {
        String safeBase = escapeHtmlAttr(baseUrl);
        // 反斜杠必须先转义：只替换单引号的话，token 末尾带 \ 会把我们补的转义符本身
        // 变成被转义的反斜杠，随后的单引号照样闭合字符串，等于在注入脚本里执行任意 JS
        String safeToken = token == null ? "" : token.replace("\\", "\\\\").replace("'", "\\'");

        // 1) base：让相对路径资源能回到原站点加载
        String baseTag = "<base href=\"" + safeBase + "\">";

        // 0) 尽量移除页面自带的 CSP meta（否则可能禁用我们注入的 inline script，导致“点了没反应”）
        String cleaned = stripCspMeta(html);

        // 2) 注入脚本：拦截 target=_blank / window.open
        String script = "<script>(function(){"
                + "var TOKEN='" + safeToken + "';"
                + "function post(type,url){try{window.parent && window.parent.postMessage({__checkbaBrowser:true,token:TOKEN,type:type,url:url},'*');}catch(e){}}"
                + "function debug(msg){post('DEBUG',String(msg||''));}"
                + "function proxify(url){try{return '/api/browser/proxy?url='+encodeURIComponent(url)+'&token='+encodeURIComponent(TOKEN);}catch(e){return url;}}"
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
                + "// 同一标签页内导航也强制走 proxy，保证后续页面仍可拦截 _blank/window.open"
                + "try{debug('click nav -> '+String(abs));}catch(e){};"
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

    private String escapeHtmlAttr(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}


