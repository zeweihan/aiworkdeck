package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.ai.PluginDevService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件开发形态接口（dev-board#61，安全模型见 {@link PluginDevService} 类注释）：
 * - POST /scaffold  {projectId, id, name}      在项目里生成插件骨架，返回 folderId
 * - GET  /status?projectId=N                    列出项目的插件源码项目与本机安装状态
 * - POST /install   {projectId, folderId}       校验并装进本机 plugins/（热重扫 + 启用）
 * - POST /uninstall {id}                        卸载开发安装（只认 .awd-dev 标记）
 *
 * 鉴权与 PluginController 的市场写操作同口径：写操作仅 admin（桌面单机全员管理员），
 * status 登录即可。错误一律 {code:1, message}（校验错误是业务错误，不是掉线，绝不带 4010）。
 */
@RestController
@RequestMapping("/api/plugins/dev")
@RequiredArgsConstructor
public class PluginDevController {

    private final PluginDevService pluginDevService;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    @PostMapping("/scaffold")
    public ResponseEntity<Map<String, Object>> scaffold(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!isAdmin(userId)) {
            return forbidden();
        }
        try {
            Long projectId = asLong(body.get("projectId"));
            String id = body.get("id") == null ? null : String.valueOf(body.get("id")).trim();
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            Long folderId = pluginDevService.scaffold(projectId, userId, id, name);
            telemetryService.record("plugin.dev", Map.of("pluginId", id, "op", "scaffold"));
            Map<String, Object> result = ok();
            result.put("folderId", folderId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam("projectId") Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        try {
            Map<String, Object> result = ok();
            result.put("items", pluginDevService.status(projectId));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> install(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!isAdmin(userId)) {
            return forbidden();
        }
        try {
            String id = pluginDevService.install(asLong(body.get("projectId")), asLong(body.get("folderId")));
            telemetryService.record("plugin.dev", Map.of("pluginId", id, "op", "install"));
            Map<String, Object> result = ok();
            result.put("id", id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/uninstall")
    public ResponseEntity<Map<String, Object>> uninstall(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!isAdmin(userId)) {
            return forbidden();
        }
        try {
            String id = body == null ? null : body.get("id");
            pluginDevService.uninstall(id);
            if (id != null) {
                telemetryService.record("plugin.dev", Map.of("pluginId", id, "op", "uninstall"));
            }
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId).map(adminAccessService::isAdmin).orElse(false);
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        return result;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
