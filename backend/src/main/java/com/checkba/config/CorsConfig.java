package com.checkba.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 全局 CORS 配置。
 *
 * 默认仅允许 localhost/127.0.0.1（开发与桌面 Electron 场景）以及配置的白名单来源携带凭证跨域。
 * 内网穿透（cpolar 等）的随机域名需在 {@code security.cors.allowed-origins} 中显式配置，
 * 或临时将 {@code security.cors.allow-all=true} 作为逃生开关恢复“回显任意来源”（不建议生产开启）。
 *
 * 此前无条件反射任意 Origin 且允许凭证，等价于任意站点可带凭证跨域访问，已收敛为白名单。
 */
@Configuration
public class CorsConfig {

    @Value("${security.cors.allowed-origins:}")
    private String allowedOriginsCsv;

    @Value("${security.cors.allow-all:false}")
    private boolean allowAll;

    @Bean
    public FilterRegistrationBean<Filter> corsFilterRegistration() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new CorsPreflightFilter(parseOrigins(allowedOriginsCsv), allowAll));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setName("corsPreflightFilter");
        return bean;
    }

    private static Set<String> parseOrigins(String csv) {
        Set<String> set = new HashSet<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    /**
     * 自定义过滤器：处理所有 CORS 请求，特别是 OPTIONS 预检请求。
     * 仅对受信来源回显 Origin 并允许携带凭证。
     */
    public static class CorsPreflightFilter implements Filter {

        private final Set<String> allowedOrigins;
        private final boolean allowAll;

        public CorsPreflightFilter(Set<String> allowedOrigins, boolean allowAll) {
            this.allowedOrigins = allowedOrigins;
            this.allowAll = allowAll;
        }

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletResponse response = (HttpServletResponse) res;
            HttpServletRequest request = (HttpServletRequest) req;

            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isEmpty() && isAllowed(origin)) {
                // 仅对受信来源回显 origin 并允许携带凭证
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Vary", "Origin");
            }
            // 不在白名单的来源：不设置 Allow-Origin，浏览器会阻止其读取响应。
            // 无 Origin 头（同源/curl）不受影响，正常处理。

            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD");
            response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization, X-Session-Id, Cache-Control, Pragma, X-File-Offset, X-File-Total-Size");
            response.setHeader("Access-Control-Max-Age", "3600");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition, X-Suggested-Filename");

            // 对于 OPTIONS 预检请求，直接返回 200，不继续处理
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }

            chain.doFilter(req, res);
        }

        private boolean isAllowed(String origin) {
            if (allowAll) return true;
            if (allowedOrigins.contains(origin)) return true;
            // 默认放行本机来源（开发与桌面 Electron 场景）
            return origin.startsWith("http://localhost:")
                    || origin.startsWith("https://localhost:")
                    || origin.equals("http://localhost")
                    || origin.equals("https://localhost")
                    || origin.startsWith("http://127.0.0.1:")
                    || origin.startsWith("https://127.0.0.1:")
                    || origin.equals("http://127.0.0.1")
                    || origin.equals("https://127.0.0.1");
        }

        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void destroy() {}
    }
}
