package com.checkba.controller.ai;

import com.checkba.service.ai.PluginService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Web 插件静态资源服务（规范 v2.3，见 docs/PLUGIN_SPEC.md §9 与
 * docs/NATIVE_PACK_DISTRIBUTION.md §11）。
 *
 * <p>把 {@code plugins/<id>/web/} 下的纯静态文件服务出来，供工作台的
 * {@code PluginPane.vue} 用 sandbox iframe 承载。
 *
 * <h3>为什么不要登录态</h3>
 * 这里只有插件包自带的静态资产，没有任何用户数据；而承载它的 iframe 带
 * {@code sandbox="allow-scripts allow-forms"}（**无** allow-same-origin），
 * 是 opaque origin，本来也带不出任何凭据。要登录既没有安全收益，还会让
 * iframe 直接白屏。插件想碰用户数据只有 postMessage 桥一条路，权限在宿主端裁剪。
 *
 * <h3>四道守卫</h3>
 * <ol>
 *   <li>id 必须过 {@link #PLUGIN_ID} 正则；</li>
 *   <li>目标文件的 canonical path 必须落在 {@code <pluginDir>/web/} 之下
 *       （{@link PluginService#resolveWebFile}，同时挡 {@code ../} 与符号链接）；</li>
 *   <li>只服务**已启用**插件：未安装 / 已禁用 / 被平台封禁一律 404，
 *       与「禁用即不加载 JAR」同一口径——禁用了就不该还能跑它的代码；</li>
 *   <li>响应带严格 CSP + {@code nosniff}：默认 {@code connect-src 'none'}，
 *       只有 manifest 声明了 {@code network} 权限的插件才放开到 {@code https:}。</li>
 * </ol>
 *
 * <p>一律 404 而不是 403：不向调用方泄露「这个 id 存在但被禁用了」。
 */
@RestController
@RequestMapping("/api/plugin-web")
@RequiredArgsConstructor
@Slf4j
public class PluginWebController {

    /** 与 PluginMarketService / NativePackService 同一套 id 规则 */
    private static final Pattern PLUGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    private static final String PREFIX = "/api/plugin-web/";

    /** 常见静态资产的 Content-Type；其余一律 octet-stream（配合 nosniff 就是「不执行」） */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"));

    /** 不声明 network 权限时的 CSP：插件出不了网，只能走 postMessage 桥 */
    static final String CSP_BASE =
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self' data:; connect-src ";

    private final PluginService pluginService;

    @GetMapping("/{id}/**")
    public ResponseEntity<byte[]> serve(@PathVariable("id") String id, HttpServletRequest request) {
        if (id == null || !PLUGIN_ID.matcher(id).matches()) {
            return ResponseEntity.notFound().build();
        }
        PluginService.PluginMetadata meta = pluginService.getPlugin(id);
        if (meta == null || !pluginService.isEnabled(id)) {
            return ResponseEntity.notFound().build();
        }
        String subPath = subPathOf(request, id);
        if (subPath == null || subPath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        File file = pluginService.resolveWebFile(pluginService.getPluginDir(id), subPath);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try {
            body = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            log.warn("Failed to read plugin web asset {}/{}: {}", id, subPath, e.getMessage());
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentTypeOf(subPath))
                .header("Content-Security-Policy", cspFor(meta))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(body);
    }

    /**
     * 取出 {@code /api/plugin-web/<id>/} 之后的子路径。
     *
     * <p>从原始 URI 自己切而不用 {@code **} 通配变量：Spring 对通配段的解码时机随
     * PathPattern 配置而变，这里统一「先切后解码」，解码结果里的 {@code ..} 由
     * {@link PluginService#resolveWebFile} 的 canonical 校验兜底。
     */
    static String subPathOf(HttpServletRequest request, String id) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        int at = uri.indexOf(PREFIX);
        if (at < 0) {
            return null;
        }
        String rest = uri.substring(at + PREFIX.length());
        if (!rest.startsWith(id)) {
            return null;
        }
        rest = rest.substring(id.length());
        if (!rest.startsWith("/")) {
            return null;
        }
        rest = rest.substring(1);
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        try {
            return URLDecoder.decode(rest, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * CSP：声明了 {@code network} 权限的插件放开 {@code connect-src https:}，否则 {@code 'none'}。
     * 这是 manifest 权限第一次成为**运行时的真实边界**——JAR 插件做不到，这里由浏览器执行。
     */
    static String cspFor(PluginService.PluginMetadata meta) {
        List<String> permissions = meta.getPermissions();
        boolean network = permissions != null && permissions.contains("network");
        return CSP_BASE + (network ? "https:" : "'none'");
    }

    static String contentTypeOf(String subPath) {
        int dot = subPath.lastIndexOf('.');
        if (dot < 0 || dot == subPath.length() - 1) {
            return "application/octet-stream";
        }
        String ext = subPath.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
