package com.checkba.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 浏览器面板代理（Web/H5 那条链路）的契约。
 *
 * <p>钉三件事：
 * <ol>
 *   <li><b>SSRF 例外名单默认关</b>——不设就照样拦回环，e2e 那个口子不能悄悄留在发行版里；</li>
 *   <li><b>注入脚本必须能解析</b>——整段拼成一行，字符串外出现 {@code //} 就会把后面全部
 *       代码注释掉（这个 bug 让 _blank/window.open 拦截、同标签跳转、postMessage 三件事
 *       静默失效了很久，页面上只留一句 SyntaxError: Unexpected end of input）；</li>
 *   <li><b>同标签跳转拼绝对地址 + 回报真实地址</b>——相对路径会被注入的 {@code <base>}
 *       解析到被访问站点头上，而 {@code URL_CHANGED} 是渲染层让 tab.url 跟随导航的唯一来源。</li>
 * </ol>
 */
class BrowserProxyControllerTest {

    private HttpServer site;
    private String siteUrl;
    private BrowserProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        // 端口 0 = 内核分配，维护者常年多开并行会话，写死端口必撞
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = "<html><head><title>T</title></head><body>去第二页</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        siteUrl = "http://127.0.0.1:" + site.getAddress().getPort() + "/a";
        controller = new BrowserProxyController();
        ReflectionTestUtils.setField(controller, "e2eAllowedHosts", "");
    }

    @AfterEach
    void tearDown() {
        if (site != null) site.stop(0);
    }

    @Test
    void loopbackBlockedWhenAllowListUnset() {
        assertEquals(HttpStatus.FORBIDDEN, controller.proxy(siteUrl, "tok").getStatusCode(),
                "例外名单为空时必须照常拦回环——这个默认值是发行版唯一会用到的那个");
    }

    @Test
    void loopbackBlockedWhenAllowListNamesAnotherHost() {
        ReflectionTestUtils.setField(controller, "e2eAllowedHosts", "example.invalid");
        assertEquals(HttpStatus.FORBIDDEN, controller.proxy(siteUrl, "tok").getStatusCode(),
                "名单是精确匹配，别的主机在名单里不等于放行本机");
    }

    @Test
    void injectedScriptParsesAndCarriesNavigationContract() {
        ReflectionTestUtils.setField(controller, "e2eAllowedHosts", "127.0.0.1");
        ResponseEntity<?> resp = controller.proxy(siteUrl, "br_tok_1");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String script = extractInjectedScript(String.valueOf(resp.getBody()));

        assertNoLineComment(script);

        // 同标签跳转必须拼绝对地址：相对路径会被上面注入的 <base href=真站点> 解析到
        // 被访问站点头上（实测跳成 http://被访站/api/browser/proxy?url=…，站点回 404）
        assertTrue(script.contains("new URL(location.href)"),
                "proxify 必须基于当前文档地址拼绝对 URL，否则会被 <base> 解析到被访问站点上");

        // 渲染层靠它让 tab.url 跟随页内导航；报的必须是真实页面地址，不是代理地址
        assertTrue(script.contains("post('URL_CHANGED',PAGE_URL)"), "每次文档加载都要回报真实地址");
        assertTrue(script.contains("var PAGE_URL='" + siteUrl + "'"),
                "PAGE_URL 应是跟完重定向之后的真实地址，实际脚本：" + script);
    }

    @Test
    void htmlResponseDeclaresCharset() {
        // 不声明 charset 的话浏览器拿默认编码（windows-1252）去解我们发出去的 UTF-8 字节，
        // 中文页面在浏览器面板里整页乱码（"去第二页" → "åŽ»ç¬¬äºŒé¡µ"）
        ReflectionTestUtils.setField(controller, "e2eAllowedHosts", "127.0.0.1");
        ResponseEntity<?> resp = controller.proxy(siteUrl, "tok");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(StandardCharsets.UTF_8, resp.getHeaders().getContentType().getCharset(),
                "回给浏览器的 HTML 必须声明 charset=utf-8");
        assertTrue(String.valueOf(resp.getBody()).contains("去第二页"), "正文不该在服务端就被解坏");
    }

    @Test
    void scriptStringEscapingSurvivesHostileUrl() {
        // 被访问站点能 302 到任意地址，页面地址不是可信输入：带 </script> 或单引号
        // 就能跳出 JS 字面量。构造真实的恶意重定向成本太高，这里直接验转义函数。
        // 不要包一层 String.valueOf：invokeMethod 的返回类型靠目标推断，包起来会选中
        // String.valueOf(char[]) 那个重载，运行时炸 ClassCastException
        String escaped = ReflectionTestUtils.invokeMethod(
                controller, "escapeJsString", "https://x/?a=</script><script>evil()&b='+alert(1)+'");
        assertFalse(escaped.contains("<"), "< 一律转 \\x3C，否则能闭合 script 标签跳出脚本：" + escaped);
        for (int i = 0; i < escaped.length(); i++) {
            if (escaped.charAt(i) == '\'') {
                assertTrue(i > 0 && escaped.charAt(i - 1) == '\\',
                        "第 " + i + " 个字符上有没转义的单引号，能闭合字面量：" + escaped);
            }
        }
    }

    /** 扫一遍字符串字面量之外的 {@code //}。脚本里没有块注释也没有除法，所以这个扫描是精确的。 */
    private void assertNoLineComment(String js) {
        boolean inStr = false;
        for (int i = 0; i < js.length() - 1; i++) {
            char c = js.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '\'') inStr = false;
                continue;
            }
            if (c == '\'') { inStr = true; continue; }
            if (c == '/' && js.charAt(i + 1) == '/') {
                fail("注入脚本是单行拼接，字符串外出现 // 行注释会把后面全部代码一起注释掉。位置 "
                        + i + "：" + js.substring(Math.max(0, i - 60), Math.min(js.length(), i + 60)));
            }
        }
    }

    private String extractInjectedScript(String html) {
        int start = html.indexOf("<script>");
        int end = html.indexOf("</script>", start);
        assertTrue(start >= 0 && end > start, "没找到注入的脚本：" + html.substring(0, Math.min(300, html.length())));
        return html.substring(start + "<script>".length(), end);
    }
}
