package com.checkba.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 2026-08-05 安全审查 F1/F2/F3 的修法（见 {@link LocalModeAccessFilter} 类注释）：
 *
 * - 跨站的状态变更请求（带非白名单 Origin）在 local-mode 下 403，而不只是不回 ACAO；
 * - 同源 / 无 Origin（curl、桌面壳 file:// 页、git 客户端）放行；
 * - 非回环 remoteAddr 一律 403（挡住「团队服务器误开 local-mode + nginx 反代」）；
 * - server 模式下以上校验全部不生效，行为一字不变。
 */
class LocalModeAccessFilterTest {

    private static final String EVIL = "https://evil.example";

    private LocalModeAccessFilter localMode() {
        return new LocalModeAccessFilter(true, "", false);
    }

    private LocalModeAccessFilter serverMode() {
        return new LocalModeAccessFilter(false, "", false);
    }

    private MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    /** 跑完整条 doFilter，返回 [是否放行, 响应]。 */
    private MockHttpServletResponse run(LocalModeAccessFilter filter, MockHttpServletRequest request,
                                        boolean[] passedThrough) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                passedThrough[0] = true;
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    // ==================== F1：跨站状态变更 ====================

    @Test
    void crossSitePostIsForbidden() throws Exception {
        // 复现审查里的可利用面：恶意页面向可枚举 fileId 发 multipart 覆写文档
        MockHttpServletRequest request = req("POST", "/api/files/123/upload");
        request.addHeader("Origin", EVIL);
        request.setContentType("multipart/form-data; boundary=x");

        boolean[] passed = {false};
        MockHttpServletResponse response = run(localMode(), request, passed);

        assertEquals(403, response.getStatus());
        assertFalse(passed[0], "跨站写请求不得进入 controller");
        assertTrue(response.getContentAsString().contains("跨站"), response.getContentAsString());
    }

    @Test
    void crossSiteDeactivateIsForbidden() throws Exception {
        // F3：匿名无 body 的简单请求，任意站点可打
        MockHttpServletRequest request = req("POST", "/api/license/deactivate");
        request.addHeader("Origin", EVIL);
        boolean[] passed = {false};
        assertEquals(403, run(localMode(), request, passed).getStatus());
        assertFalse(passed[0]);
    }

    @Test
    void nullLiteralOriginIsForbidden() {
        // sandbox iframe / file:// 恶意页面的来源就是字面量 "null"，不能当成「没有来源」
        MockHttpServletRequest request = req("POST", "/api/files/1/upload");
        request.addHeader("Origin", "null");
        assertNotNull(localMode().denialReason(request));
    }

    @Test
    void requestWithoutOriginIsAllowed() throws Exception {
        // 打包态桌面壳（file:// + webSecurity=false）实测不带 Origin；curl / git 客户端同理
        MockHttpServletRequest request = req("POST", "/api/files/1/upload");
        boolean[] passed = {false};
        assertEquals(200, run(localMode(), request, passed).getStatus());
        assertTrue(passed[0], "无 Origin 的本机请求必须放行，否则打断桌面壳与 GitHttpController");
    }

    @Test
    void trustedLocalhostOriginsAreAllowed() {
        // 开发态渲染进程 5173 / app-e2e 5174 / 127.0.0.1 任意端口
        for (String origin : new String[]{
                "http://localhost:5173", "http://localhost:5174",
                "http://127.0.0.1:9696", "https://localhost"}) {
            MockHttpServletRequest request = req("POST", "/api/projects");
            request.addHeader("Origin", origin);
            assertNull(localMode().denialReason(request), "应放行受信来源: " + origin);
        }
    }

    @Test
    void configuredOriginIsAllowed() {
        LocalModeAccessFilter filter = new LocalModeAccessFilter(true, "https://tunnel.example", false);
        MockHttpServletRequest request = req("POST", "/api/projects");
        request.addHeader("Origin", "https://tunnel.example");
        assertNull(filter.denialReason(request));
    }

    @Test
    void allowAllEscapeHatchDisablesTheBlock() {
        LocalModeAccessFilter filter = new LocalModeAccessFilter(true, "", true);
        MockHttpServletRequest request = req("POST", "/api/projects");
        request.addHeader("Origin", EVIL);
        assertNull(filter.denialReason(request), "allow-all 逃生开关应同时放开硬拦截，与 CORS 口径一致");
    }

    @Test
    void crossSiteGetIsAllowed() throws Exception {
        // GET 不改状态，且跨站读仍被浏览器的 CORS（不回 ACAO）挡住
        MockHttpServletRequest request = req("GET", "/api/license/status");
        request.addHeader("Origin", EVIL);
        boolean[] passed = {false};
        assertEquals(200, run(localMode(), request, passed).getStatus());
        assertTrue(passed[0]);
    }

    @Test
    void preflightIsNotBlocked() {
        // OPTIONS 交给 CorsConfig 处理：不回 ACAO，浏览器自会拦死后续真实请求
        MockHttpServletRequest request = req("OPTIONS", "/api/files/1/upload");
        request.addHeader("Origin", EVIL);
        assertNull(localMode().denialReason(request));
    }

    // ==================== F2：非回环来源 ====================

    @Test
    void nonLoopbackRemoteAddrIsForbiddenForEveryMethod() throws Exception {
        for (String method : new String[]{"GET", "POST", "PUT", "DELETE"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/projects/my");
            request.setRemoteAddr("203.0.113.7");
            boolean[] passed = {false};
            MockHttpServletResponse response = run(localMode(), request, passed);
            assertEquals(403, response.getStatus(), method + " 非回环来源应被拒");
            assertFalse(passed[0], method + " 非回环来源不得进入 controller");
        }
    }

    @Test
    void forwardedForHeaderCannotFakeLoopback() {
        // X-Forwarded-For 是客户端可伪造的头，不得拿它把非回环来源洗成回环
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertNotNull(localMode().denialReason(request), "不得因 X-Forwarded-For 而放行非回环来源");
    }

    @Test
    void sameHostReverseProxyIsForbidden() throws Exception {
        // F2 的真实形态：deploy/web 基线里 nginx 与后端同机，反代过来的 remoteAddr
        // 就是 127.0.0.1，只查回环会原样放行——必须靠反代痕迹头识别出来
        for (String header : new String[]{
                "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Proto", "Forwarded"}) {
            MockHttpServletRequest request = req("GET", "/api/projects/my");
            request.addHeader(header, "203.0.113.7");
            boolean[] passed = {false};
            MockHttpServletResponse response = run(localMode(), request, passed);
            assertEquals(403, response.getStatus(), header + ": 同机反代必须被识别并拒绝");
            assertFalse(passed[0], header + ": 反代请求不得进入 controller");
            assertTrue(response.getContentAsString().contains("反向代理"),
                    response.getContentAsString());
        }
    }

    @Test
    void directDesktopRequestCarriesNoProxyHeaders() {
        // 渲染进程直连 127.0.0.1，不带任何反代痕迹头 —— 不能被上一条误伤
        assertNull(localMode().denialReason(req("POST", "/api/projects")));
    }

    @Test
    void ipv6LoopbackIsAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects");
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        assertNull(localMode().denialReason(request));
    }

    // ==================== server 模式：行为一字不变 ====================

    @Test
    void serverModeIsUntouched() throws Exception {
        Object[][] cases = {
                {"POST", EVIL, "203.0.113.7"},
                {"POST", EVIL, "127.0.0.1"},
                {"DELETE", "null", "10.0.0.9"},
                {"GET", null, "198.51.100.4"},
        };
        for (Object[] c : cases) {
            MockHttpServletRequest request = new MockHttpServletRequest((String) c[0], "/api/projects");
            if (c[1] != null) request.addHeader("Origin", (String) c[1]);
            // 团队服务器就是 nginx 反代过来的，这些头必须照常放行
            request.addHeader("X-Forwarded-For", "203.0.113.7");
            request.addHeader("X-Real-IP", "203.0.113.7");
            request.setRemoteAddr((String) c[2]);
            boolean[] passed = {false};
            MockHttpServletResponse response = run(serverMode(), request, passed);
            assertEquals(200, response.getStatus(), "server 模式不得改变行为: " + c[0] + " " + c[1]);
            assertTrue(passed[0], "server 模式必须原样放行: " + c[0] + " " + c[1]);
        }
    }
}
