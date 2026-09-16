// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import com.checkba.service.AppLanguageScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@code X-App-Language} 请求头 → 请求级语言作用域（dev-board#713）。
 */
class AppLanguageRequestFilterTest {

    private final AppLanguageRequestFilter filter = new AppLanguageRequestFilter();

    /** 记录过滤链执行那一刻线程上生效的语言。 */
    private String languageSeenBy(String header) throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(AppLanguageRequestFilter.HEADER)).thenReturn(header);
        HttpServletResponse response = mock(HttpServletResponse.class);
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = AppLanguageScope.current();
        filter.doFilter(request, response, chain);
        assertNull(AppLanguageScope.current(), "过滤器返回后必须把作用域恢复干净");
        return seen[0];
    }

    @Test
    @DisplayName("声明了语言：整条请求处理链上的文案按该语言产出")
    void headerEstablishesScopeForTheWholeChain() throws Exception {
        assertEquals("en-US", languageSeenBy("en-US"));
        assertEquals("en-US", languageSeenBy("en"));
        assertEquals("zh-CN", languageSeenBy("zh-CN"));
    }

    @Test
    @DisplayName("不声明 / 认不出来：不建作用域，行为与引入前逐字节一致（桌面端走这条）")
    void missingOrUnknownHeaderLeavesNoOverride() throws Exception {
        assertNull(languageSeenBy(null));
        assertNull(languageSeenBy(""));
        assertNull(languageSeenBy("ja-JP"));
    }

    @Test
    @DisplayName("链上抛异常照样恢复作用域，且异常原样抛回容器")
    void scopeIsRestoredWhenChainThrows() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(AppLanguageRequestFilter.HEADER)).thenReturn("en-US");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain boom = (req, res) -> {
            throw new IOException("downstream failed");
        };
        assertThrows(IOException.class, () -> filter.doFilter(request, response, boom));
        assertNull(AppLanguageScope.current());
    }

    @Test
    @DisplayName("语言头在 CORS 白名单里：窗格与后端分域部署时不会卡在预检上")
    void headerIsAllowedByCors() {
        // 白名单是一整串字面量，漏了这个头的症状不是报错而是「换个部署形态整条链不通」
        assertTrue(corsAllowedHeaders().contains(AppLanguageRequestFilter.HEADER));
    }

    private static String corsAllowedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("OPTIONS");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringBuilder captured = new StringBuilder();
        doAnswer(inv -> {
            if ("Access-Control-Allow-Headers".equals(inv.getArgument(0))) {
                captured.append((String) inv.getArgument(1));
            }
            return null;
        }).when(response).setHeader(anyString(), anyString());
        try {
            new CorsConfig.CorsPreflightFilter(java.util.Set.of(), false)
                    .doFilter(request, response, (req, res) -> { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return captured.toString();
    }
}
