package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.repository.UserRepository;
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
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final UserRepository userRepository;

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

    private ResponseEntity<Map<String, Object>> setEnabled(String pluginId, boolean enabled, String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("仅管理员可操作"));
        }
        try {
            pluginService.setEnabled(pluginId, enabled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("插件不存在: " + pluginId));
        }
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
        return view;
    }

    /** 与 AdminConfigController 相同的管理员判定：session -> userId -> username == admin */
    private boolean isAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(u -> "admin".equalsIgnoreCase(u.getUsername()))
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
