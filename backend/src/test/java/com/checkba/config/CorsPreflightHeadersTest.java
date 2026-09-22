// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 预检放行的请求头清单，必须覆盖客户端真正会发的每一个自定义头。
 *
 * <p>回归背景（dev-board#803）：桌面端 SSE 建连开始携带 {@code X-Client-Instance} 与
 * {@code Last-Event-ID} 之后，页面与后端是两个 origin（页面在 dev/打包端口上，后端在
 * 127.0.0.1:52xx），自定义头必然触发预检。清单里漏掉这两个名字时，<b>整条 SSE 建连被浏览器
 * 拦在预检上</b>——对话完全不出字，而后端日志里干干净净，因为请求根本没到服务器。
 * 本机实测就是这么撞上的，所以这条用例把「客户端发什么」与「预检放行什么」钉在一起。
 */
class CorsPreflightHeadersTest {

    private List<String> allowedHeaders(String origin) throws Exception {
        CorsConfig.CorsPreflightFilter filter =
                new CorsConfig.CorsPreflightFilter(Set.of(), false);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/agent/connect/conv-1");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        String raw = response.getHeader("Access-Control-Allow-Headers");
        assertNotNull(raw, "预检必须回 Access-Control-Allow-Headers");
        return Arrays.stream(raw.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
    }

    @Test
    @DisplayName("客户端真正会发的自定义头都在预检放行清单里")
    void everyCustomHeaderTheClientSendsIsPreflightAllowed() throws Exception {
        List<String> allowed = allowedHeaders("http://127.0.0.1:5173");
        // 顺序无关，名字必须在。大小写按 HTTP 语义忽略。
        for (String header : List.of(
                "x-session-id",      // 全站鉴权
                "x-app-language",    // 界面语言（AppLanguageRequestFilter）
                "x-client-instance", // SSE 窗口身份（dev-board#803）
                "last-event-id",     // SSE 断点续传游标（dev-board#803）
                "content-type",
                "x-file-offset", "x-file-total-size")) { // 断点续传上传
            assertTrue(allowed.contains(header),
                    "预检放行清单缺少 " + header + "，带这个头的请求会被浏览器拦在预检上；实际清单=" + allowed);
        }
    }

    @Test
    @DisplayName("不受信来源仍不回显 Origin，放行清单不是放开跨域的口子")
    void untrustedOriginStillGetsNoAllowOrigin() throws Exception {
        CorsConfig.CorsPreflightFilter filter =
                new CorsConfig.CorsPreflightFilter(Set.of(), false);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/agent/connect/conv-1");
        request.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertNull(response.getHeader("Access-Control-Allow-Credentials"));
    }
}
