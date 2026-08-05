package com.checkba.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 单机免登模式的每请求准入闸（2026-08-05 安全审查 F1 / F2 / F3）。
 *
 * 背景：local-mode 下 {@code AuthController.getUserIdFromSession} 无视请求头一律解析为本机用户，
 * 于是「必须携带自定义头 X-Session-Id」这条事实上的 CSRF 防线消失了——跨源请求原本会因为
 * 自定义头触发预检、预检拿不到 ACAO 而被浏览器拦死，免登之后不带任何头也能通过鉴权。
 * 而 {@link CorsConfig} 只是「不回显 ACAO」，并不阻断请求本身：multipart 表单提交这类
 * 「简单请求」照样会执行到 controller（POST /api/files/&#123;fileId&#125;/upload 是就地覆盖字节，
 * fileId 又是可枚举的数字主键，恶意页面可批量覆写用户文档）。
 *
 * 本过滤器补两条闸，均只在 local-mode 下生效，server（团队服务器）模式行为一字不变：
 *
 * <ol>
 *   <li><b>跨站硬拦截</b>：非 GET/HEAD/OPTIONS/TRACE 的请求，若带了 Origin 且不在
 *       {@link CorsConfig} 白名单内，直接 403，而不只是不回 ACAO。
 *       浏览器对任何跨源的非 GET/HEAD 请求（含 form / multipart 这类简单请求）必发 Origin，
 *       所以这一条即可闭合 F1；file:// 恶意页面的 Origin 是字面量 "null"，同样不在白名单。
 *       <br>缺 Origin 一律放行：同源请求、curl、桌面壳内部调用、git 客户端
 *       （{@code GitHttpController} 的 Basic 认证链路）通常都不带 Origin。
 *       已实测：打包态桌面壳的渲染进程是 file:// 页（webSecurity=false），
 *       向 127.0.0.1 发的 POST/multipart <b>完全不带 Origin 头</b>，落在放行分支；
 *       开发态渲染进程是 http://localhost:5173、e2e 是 http://localhost:5174，
 *       两者都命中白名单的 localhost 分支。</li>
 *   <li><b>回环来源校验</b>（F2）：{@link LocalModeLoopbackGuard} 只证明「绑定在回环」，
 *       不证明「外部不可达」——团队版基线本就是 127.0.0.1 + nginx 反代，
 *       谁要是给团队服务器设了 SECURITY_LOCAL_MODE=true，守卫会放行，而 nginx 把零鉴权
 *       后端反代到公网。这里逐请求校验真实对端地址，非回环直接 403。
 *       <br>判定只用 {@code getRemoteAddr()}：X-Forwarded-For 是客户端可伪造的头，
 *       拿它认「来源是谁」等于没做。
 *       <br><b>但只查 remoteAddr 不足以闭合 F2</b>：deploy/web 基线里 nginx 与后端同机，
 *       反代过来的 remoteAddr 恰恰就是 127.0.0.1，回环校验会原样放行。故再加一条——
 *       local-mode 下出现任何反代痕迹头（X-Forwarded-* / X-Real-IP / Forwarded）一律 403。
 *       这不是「信任」这些头，而是把它们的出现本身当作「这台机器被反代了」的信号：
 *       单机桌面版的渲染进程直连 127.0.0.1，永远不会带这些头；本机攻击者自行添加
 *       也只会让自己的请求被拒，无利可图。</li>
 * </ol>
 *
 * 顺带覆盖 F3：{@code POST /api/license/deactivate} 匿名无 body（简单请求），
 * 任意站点都能打掉用户已存的 account 模式与明文 awdk_ Key，现在落在第 1 条闸内。
 */
@Component
@Slf4j
public class LocalModeAccessFilter implements Filter, Ordered {

    /** 不改变服务端状态的方法，跨站也无所谓（GET 的读放行由回环校验兜底）。 */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** 反代痕迹头：单机模式下出现任何一个都说明请求不是渲染进程直连过来的。 */
    private static final Set<String> PROXY_HEADERS = Set.of(
            "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
            "X-Forwarded-Port", "X-Real-IP", "Forwarded");

    private final boolean localMode;
    private final Set<String> allowedOrigins;
    private final boolean allowAll;

    public LocalModeAccessFilter(
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${security.cors.allowed-origins:}") String allowedOriginsCsv,
            @Value("${security.cors.allow-all:false}") boolean allowAll) {
        this.localMode = localMode;
        this.allowedOrigins = CorsConfig.parseOrigins(allowedOriginsCsv);
        this.allowAll = allowAll;
    }

    /** 抢在 CorsConfig 之前跑：被拒的请求不该拿到任何 CORS 响应头。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!localMode || !(req instanceof HttpServletRequest request)) {
            chain.doFilter(req, res);
            return;
        }
        String denial = denialReason(request);
        if (denial != null) {
            log.warn("单机模式拒绝请求: {} {} remoteAddr={} origin={} 原因={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                    request.getHeader("Origin"), denial);
            reject((HttpServletResponse) res, denial);
            return;
        }
        chain.doFilter(req, res);
    }

    /** 返回拒绝原因（中文，可直接回给前端）；null 表示放行。包可见供单测直接驱动。 */
    String denialReason(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return "单机模式仅接受本机请求";
        }
        for (String header : PROXY_HEADERS) {
            if (request.getHeader(header) != null) {
                return "单机模式不接受经反向代理转发的请求";
            }
        }
        String method = request.getMethod() == null
                ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        if (SAFE_METHODS.contains(method)) return null;

        String origin = request.getHeader("Origin");
        // 缺 Origin = 同源 / 非浏览器客户端，放行；有 Origin 则必须在白名单内。
        if (origin == null || origin.isEmpty()) return null;
        if (CorsConfig.isTrustedOrigin(allowedOrigins, allowAll, origin)) return null;
        return "跨站请求已被拒绝";
    }

    /**
     * 对端是否回环。remoteAddr 是字面量 IP，{@code getByName} 不会触发 DNS 查询。
     * 解析不出来（畸形值）按非回环处理——安全侧的默认拒绝。
     */
    private static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) return false;
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        // 手写 JSON：这一层在 Spring MVC 之前，拿不到 ObjectMapper 也不该为一句话引入依赖。
        response.getWriter().write("{\"code\":-1,\"message\":\"" + message + "\"}");
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
