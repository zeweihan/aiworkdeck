// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import cn.hutool.json.JSONUtil;
import com.checkba.controller.AuthController;
import com.checkba.service.DdCloudMigrationService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.version.CloudSyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已放进案件库的案卷，尽调清单以案件库为准（dev-board#1050）：桌面端（local-mode）本机的
 * {@code /api/dd/*} 在这种案卷上整体转发到 {@code {server}/api/dd/*}，本机库不再读写。
 *
 * <p>做成 servlet 过滤器而不是在 DdController 里逐端点分流，是为了**原字节**转发：
 * DispatcherServlet 一碰 multipart 就把请求体解析掉了，过滤器这一层请求体还是原样的
 * （boundary 一并保留），上传口因此不必重新拼表单。也因此这里**绝不能调
 * {@code request.getParameter}**——Tomcat 会顺手把 multipart 请求体吃掉；查询串自己拆。
 *
 * <p>路由判据：local-mode，且能定出项目 id（路径 {@code /api/dd/projects/{id}}，或其余端点
 * 前端带的 {@code ?projectId=}），且该项目有 project_remote 绑定。定不出项目 id 的请求原样
 * 放给 DdController 走本机——清单/清单项 id 在本机与案件库是两个 id 空间，前端对放进案件库的
 * 案卷必须带 projectId（api.js 的 dd 系列统一带）。
 *
 * <p>鉴权与 CloudController 的项目级端点同口径：成员且不是客户。转发用这台机器连案件库的
 * 设备令牌，案件库那一侧再按律师在案件库上的角色判一次。
 */
@Component
public class DdCloudProxyFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(DdCloudProxyFilter.class);

    static final String PREFIX = "/api/dd";
    private static final Pattern PROJECT_PATH = Pattern.compile("^/projects/(\\d+)(/.*)?$");

    private final CloudSyncService cloudSyncService;
    private final DdCloudMigrationService migrationService;
    private final ProjectMemberService projectMemberService;
    private final boolean localMode;

    public DdCloudProxyFilter(CloudSyncService cloudSyncService,
                              DdCloudMigrationService migrationService,
                              ProjectMemberService projectMemberService,
                              @Value("${security.local-mode:false}") boolean localMode) {
        this.cloudSyncService = cloudSyncService;
        this.migrationService = migrationService;
        this.projectMemberService = projectMemberService;
        this.localMode = localMode;
    }

    /** 在准入闸（HIGHEST_PRECEDENCE）之后；X-Source-Code 告示由本过滤器在短路时自己补。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!localMode) return true;
        String path = pathWithinApp(request);
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String suffix = pathWithinApp(request).substring(PREFIX.length());
        List<String[]> query = parseQuery(request.getQueryString());
        Long projectId = projectIdOf(suffix, query);
        if (projectId == null || !cloudSyncService.hasRemoteBinding(projectId)) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(SourceCodeNoticeFilter.HEADER, SourceCodeNoticeFilter.SOURCE_URL);
        String session = request.getHeader("X-Session-Id");
        if (session == null) session = value(query, "token");
        Long userId = AuthController.getUserIdFromSession(session);
        if (userId == null || !projectMemberService.hasReadPermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            writeJson(response, 200, Map.of("code", 1,
                    "message", LangText.of("无权访问该资源", "You don't have permission to access this resource")));
            return;
        }

        String method = request.getMethod();
        try {
            if ("GET".equals(method) && suffix.matches("^/projects/\\d+/?$")) {
                migrationService.migratePending(projectId);
            }
            byte[] body = request.getInputStream().readAllBytes();
            CloudSyncService.RawResponse r = cloudSyncService.proxyDd(projectId, method, suffix,
                    forwardedQuery(query), body, request.getContentType());
            response.setStatus(r.status());
            if (r.contentType() != null) response.setContentType(r.contentType());
            if (r.contentDisposition() != null) response.setHeader("Content-Disposition", r.contentDisposition());
            byte[] out = r.body() == null ? new byte[0] : r.body();
            response.setContentLength(out.length);
            response.getOutputStream().write(out);
        } catch (Exception e) {
            log.warn("尽调清单代理到案件库失败: project={} {} {}", projectId, method, suffix, e);
            writeJson(response, 200, Map.of("code", 1, "message", LangText.of(
                    "团队案件库暂时连不上，尽调清单稍后再看", "The Team Case Library is unreachable right now; please try the checklist again later")));
        }
    }

    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    static Long projectIdOf(String suffix, List<String[]> query) {
        Matcher m = PROJECT_PATH.matcher(suffix);
        String raw = m.matches() ? m.group(1) : value(query, "projectId");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<String[]> parseQuery(String qs) {
        List<String[]> out = new ArrayList<>();
        if (qs == null || qs.isEmpty()) return out;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.add(new String[]{URLDecoder.decode(k, StandardCharsets.UTF_8), v});
        }
        return out;
    }

    private static String value(List<String[]> query, String key) {
        for (String[] kv : query) {
            if (key.equals(kv[0])) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    /** projectId（本机 id，案件库上没意义）与 token（本机会话）不往外带。 */
    static String forwardedQuery(List<String[]> query) {
        StringBuilder sb = new StringBuilder();
        for (String[] kv : query) {
            if ("projectId".equals(kv[0]) || "token".equals(kv[0])) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(java.net.URLEncoder.encode(kv[0], StandardCharsets.UTF_8)).append('=').append(kv[1]);
        }
        return sb.toString();
    }

    private static void writeJson(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(JSONUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8));
    }
}
