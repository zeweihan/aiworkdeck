package com.checkba.controller;

import com.checkba.controller.AdminConfigController.AdminConfigUpdateRequest;
import com.checkba.repository.SystemSettingRepository;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 首次运行向导接口（Epic #18 T3）：
 * - GET  /api/admin/wizard  查询是否已初始化（供前端决定是否进入向导）
 * - POST /api/admin/wizard  一次性写入初始配置（复用 AdminConfig 的 DTO 与 key 映射）
 *
 * 安全模型：仅在"未初始化"状态下可匿名调用。未初始化 = 向导未完成且
 * system_setting 表为空（从未通过管理后台或向导保存过任何配置）；
 * 满足任一条件即视为已初始化，POST 返回 409，后续配置修改一律走
 * /api/admin/config（需管理员会话）。
 * 注意不能用"是否存在用户"判断：DataInitializer 启动时会自动创建默认 admin。
 */
@RestController
@RequestMapping("/api/admin/wizard")
@CrossOrigin(origins = "*")
public class WizardController {

    static final String KEY_WIZARD_COMPLETED = "system.wizard.completed";

    private final SystemSettingService systemSettingService;
    private final SystemSettingRepository systemSettingRepository;
    private final ObjectMapper objectMapper;

    public WizardController(SystemSettingService systemSettingService,
                            SystemSettingRepository systemSettingRepository,
                            ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.systemSettingRepository = systemSettingRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<?> status() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 0);
        body.put("initialized", isInitialized());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public synchronized ResponseEntity<?> initialize(@RequestBody AdminConfigUpdateRequest request) {
        // synchronized：串行化初始化，堵住两个并发匿名 POST 同时通过 isInitialized()==false 的 TOCTOU 竞态
        if (isInitialized()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("系统已初始化，请通过管理后台修改配置 / Already initialized; use the admin console instead"));
        }
        if (request == null || request.getAi() == null
                || request.getAi().getActiveProvider() == null
                || request.getAi().getActiveProvider().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(error("必须选择一个 AI 提供商 / An AI provider must be selected"));
        }

        Map<String, String> updates;
        try {
            updates = AdminConfigController.toSettingsUpdates(request, objectMapper);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
        updates.put(KEY_WIZARD_COMPLETED, "true");
        systemSettingService.setMany(updates);

        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "初始化完成 / Initialized");
        return ResponseEntity.ok(ok);
    }

    private boolean isInitialized() {
        if (Boolean.parseBoolean(systemSettingService.get(KEY_WIZARD_COMPLETED, "false"))) {
            return true;
        }
        // 保存过任何配置的存量部署视为已初始化，防止向导端点被匿名滥用
        return systemSettingRepository.count() > 0;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
