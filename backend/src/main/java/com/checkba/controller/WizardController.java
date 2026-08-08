package com.checkba.controller;

import com.checkba.controller.AdminConfigController.AdminConfigUpdateRequest;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 首次运行向导接口（Epic #18 T3）：
 * - GET  /api/admin/wizard        查询是否已初始化（供前端决定是否进入向导）
 * - POST /api/admin/wizard        一次性写入初始配置（复用 AdminConfig 的 DTO 与 key 映射）
 * - POST /api/admin/wizard/reset  管理员重置向导（允许再走一遍——存量安装换 Key/换提供商的入口）
 *
 * 安全模型：仅"全新安装"（completed 标记从未写过且 system_setting 为空）可匿名调用一次；
 * 标记一旦写过（含管理员 reset 置 "false" 重开的窗口）就必须带管理员会话，
 * 否则任何能连到本机的人都能在窗口期改写 AI baseUrl 与系统提示词，
 * 把全体用户的模型流量与文档上下文导向攻击者的服务端。
 * 后续配置修改一律走 /api/admin/config（需管理员会话）。
 * 注意不能用"是否存在用户"判断：DataInitializer 启动时会自动创建默认 admin。
 */
@RestController
@RequestMapping("/api/admin/wizard")
public class WizardController {

    /**
     * 向导完成标记的键。定义在 {@link com.checkba.service.WizardStateService}——
     * 「是否已初始化」同时是一条安全前置条件（匿名窗口的边界），只许有一处定义。
     */
    public static final String KEY_WIZARD_COMPLETED =
            com.checkba.service.WizardStateService.KEY_WIZARD_COMPLETED;

    private final SystemSettingService systemSettingService;
    private final com.checkba.service.WizardStateService wizardStateService;
    private final UserRepository userRepository;
    private final com.checkba.service.AdminAccessService adminAccessService;
    private final ObjectMapper objectMapper;
    private final com.checkba.service.ai.ChatModelFactory chatModelFactory;

    public WizardController(SystemSettingService systemSettingService,
                            com.checkba.service.WizardStateService wizardStateService,
                            UserRepository userRepository,
                            com.checkba.service.AdminAccessService adminAccessService,
                            ObjectMapper objectMapper,
                            com.checkba.service.ai.ChatModelFactory chatModelFactory) {
        this.systemSettingService = systemSettingService;
        this.wizardStateService = wizardStateService;
        this.userRepository = userRepository;
        this.adminAccessService = adminAccessService;
        this.objectMapper = objectMapper;
        this.chatModelFactory = chatModelFactory;
    }

    @GetMapping
    public ResponseEntity<?> status() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 0);
        body.put("initialized", isInitialized());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public synchronized ResponseEntity<?> initialize(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody AdminConfigUpdateRequest request) {
        // synchronized：串行化初始化，堵住两个并发匿名 POST 同时通过 isInitialized()==false 的 TOCTOU 竞态
        if (isInitialized()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("系统已初始化，请通过管理后台修改配置 / Already initialized; use the admin console instead"));
        }
        // completed 标记存在即说明本机已被初始化过一次（当前为管理员 reset 重开的窗口）：
        // 该窗口由管理员主动打开，重新提交必须携带管理员会话，否则就是一个可被
        // 匿名利用的改写 AI baseUrl / 系统提示词的入口。全新安装（标记从未写过）
        // 此时还没有任何人登录过，仍按向导本职放行匿名提交一次。
        if (systemSettingService.get(KEY_WIZARD_COMPLETED, null) != null
                && requireAdmin(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("仅管理员可重新运行向导 / Admin only"));
        }
        // 拒空 activeProvider 是两道闸门里的后一道（前一道在 wizard.vue 的 handleSubmit）：
        // 向导刻意不预选供应商（唯一例外是已连接账户且已分配额度时预选平台通道），
        // 预选会让没装 Ollama 的用户一路点到发第一条消息才收到 Connection refused。
        // 取值本身的合法性（三档枚举）由 toSettingsUpdates 统一校验。
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
        // 向导保存的 key/供应商立即生效（与管理后台保存行为一致）
        chatModelFactory.clearCache();

        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "初始化完成 / Initialized");
        return ResponseEntity.ok(ok);
    }

    /**
     * 管理员重置向导：completed 置 "false"，之后向导页可再走一遍（提交成功
     * 自动置回 "true"）。窗口期内 POST /api/admin/wizard 仍要求管理员会话——
     * 重置由管理员在已登录状态下发起，reLaunch 到向导页会话仍在，不影响该流程。
     *
     * <p>注意这里只动 completed 标记，不清任何配置：重跑向导时只有向导真正填了的字段
     * 会被覆盖（{@code AdminConfigController.toSettingsUpdates} 跳过 null 字段，
     * 不会再把同组里没填的 baseUrl/secret 清成空串）。
     */
    @PostMapping("/reset")
    public ResponseEntity<?> reset(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        User admin = requireAdmin(sessionId);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("仅管理员可重置向导 / Admin only"));
        }
        Map<String, String> updates = new HashMap<>();
        updates.put(KEY_WIZARD_COMPLETED, "false");
        systemSettingService.setMany(updates);

        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "向导已重置，可重新运行 / Wizard reset");
        return ResponseEntity.ok(ok);
    }

    private boolean isInitialized() {
        return wizardStateService.isInitialized();
    }

    private User requireAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return null;
        return userRepository.findById(userId)
                .filter(adminAccessService::isAdmin)
                .orElse(null);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
