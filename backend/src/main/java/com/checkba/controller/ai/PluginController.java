package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.ai.PluginService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件广场接口（manifest 规范 v1 见 docs/PLUGIN_SPEC.md）：
 * - GET  /list          插件列表（含工具清单、权限声明、启用状态），登录即可查看
 * - POST /{id}/enable   启用插件（仅 admin）
 * - POST /{id}/disable  禁用插件（仅 admin）
 * - POST /rescan        重新扫描 plugins/ 目录（仅 admin）
 * - GET  /market/list          在线插件广场列表，登录即可查看
 * - POST /market/install {id}  验签安装（仅 admin，装后默认禁用待确认）
 * - POST /market/uninstall{id} 卸载（仅 admin）
 * - POST /market/sync-revoked  手动同步平台封禁列表（仅 admin）
 *
 * 在线安装的安全模型见 docs/PLUGIN_DISTRIBUTION.md。
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final com.checkba.service.ai.PluginMarketService pluginMarketService;
    private final com.checkba.service.ai.PluginRevocationService revocationService;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    @lombok.Data
    public static class PluginView {
        private String id;
        private String name;
        private String version;
        private String description;
        private String icon;
        private String author;
        private String homepage;
        private String frontendEntry;
        private List<String> permissions;
        private List<PluginService.PluginToolInfo> tools;
        private int toolCount;
        private boolean enabled;
        /** 被平台封禁时的原因；非空表示该插件已下架，界面应标红并禁止启用 */
        private String revokedReason;
    }

    @GetMapping("/list")
    public List<PluginView> listPlugins() {
        return pluginService.getPlugins().stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enablePlugin(
            @PathVariable("id") String pluginId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return setEnabled(pluginId, true, sessionId);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disablePlugin(
            @PathVariable("id") String pluginId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return setEnabled(pluginId, false, sessionId);
    }

    @PostMapping("/rescan")
    public ResponseEntity<Map<String, Object>> rescan(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        pluginService.rescan();
        Map<String, Object> result = ok();
        result.put("pluginCount", pluginService.getPlugins().size());
        result.put("toolCount", pluginService.getPluginTools().size());
        return ResponseEntity.ok(result);
    }

    /**
     * 在线广场列表；注册表不可达返回 {code:1, message}，不影响本地插件区块。
     *
     * 要求登录：响应里带 purchased / accountConnected，属账户隐私，团队服务器部署下不能匿名可读
     * （与 EntitlementController 同口径）。
     */
    @GetMapping("/market/list")
    public ResponseEntity<Map<String, Object>> listMarket(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("未登录"));
        }
        try {
            Map<String, Object> result = ok();
            result.put("plugins", pluginMarketService.listMarket());
            // 付费项按钮形态取决于账户是否已连接（未连接显示「需连接账户」），随列表一起给
            result.put("accountConnected", pluginMarketService.accountConnected());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /** 验签安装；成功后插件处于禁用状态，需用户在广场确认启用 */
    @PostMapping("/market/install")
    public ResponseEntity<Map<String, Object>> installMarketPlugin(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        try {
            String id = pluginMarketService.install(body == null ? null : body.get("id"));
            telemetryService.record("plugin.lifecycle", Map.of("pluginId", id, "op", "install"));
            Map<String, Object> result = ok();
            result.put("id", id);
            result.put("pendingEnable", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/market/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallMarketPlugin(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        try {
            String id = body == null ? null : body.get("id");
            pluginMarketService.uninstall(id);
            if (id != null) {
                telemetryService.record("plugin.lifecycle", Map.of("pluginId", id, "op", "uninstall"));
            }
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /** 手动同步平台封禁列表（自动同步为启动时 + 每 24 小时） */
    @PostMapping("/market/sync-revoked")
    public ResponseEntity<Map<String, Object>> syncRevoked(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        Map<String, Object> result = ok();
        result.put("disabled", revocationService.sync());
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> setEnabled(String pluginId, boolean enabled, String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        try {
            pluginService.setEnabled(pluginId, enabled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("插件不存在: " + pluginId));
        } catch (IllegalStateException e) {
            // 被平台封禁的插件不允许重新启用
            return ResponseEntity.ok(error(e.getMessage()));
        }
        telemetryService.record("plugin.lifecycle",
                Map.of("pluginId", pluginId, "op", enabled ? "enable" : "disable"));
        return ResponseEntity.ok(ok());
    }

    private PluginView toView(PluginService.PluginMetadata meta) {
        PluginView view = new PluginView();
        view.setId(meta.getId());
        view.setName(meta.getName());
        view.setVersion(meta.getVersion());
        view.setDescription(meta.getDescription());
        view.setIcon(meta.getIcon());
        view.setAuthor(meta.getAuthor());
        view.setHomepage(meta.getHomepage());
        view.setFrontendEntry(meta.getFrontendEntry());
        view.setPermissions(meta.getPermissions() == null ? List.of() : meta.getPermissions());
        view.setTools(meta.getTools() == null ? List.of() : meta.getTools());
        view.setToolCount(view.getTools().size());
        view.setEnabled(pluginService.isEnabled(meta.getId()));
        view.setRevokedReason(pluginService.revokedReason(meta.getId()));
        return view;
    }

    /** 与 AdminConfigController 相同的管理员判定：session -> userId -> AdminAccessService（桌面单机全员管理员的唯一出口） */
    private boolean isAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(adminAccessService::isAdmin)
                .orElse(false);
    }

    private Map<String, Object> ok() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
