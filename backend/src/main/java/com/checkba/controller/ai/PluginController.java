package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.tools.ToolContext;
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
    private final ToolRegistry toolRegistry;
    private final com.checkba.service.ProjectMemberService projectMemberService;
    /** 声明式贡献点（规范 v2.9 P4）：模板/画像/设置 */
    private final com.checkba.service.ai.PluginContributionService contributionService;
    // 以下四个只服务 aiComplete（规范 v2.7 P2 桥 ai.request 的服务端落点）
    private final com.checkba.service.plugin.PluginHostFactory pluginHostFactory;
    private final com.checkba.service.ai.ChatModelFactory chatModelFactory;
    private final com.checkba.service.ai.AuxModelResolver auxModelResolver;
    private final com.checkba.service.ai.TokenUsageService tokenUsageService;

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
        /** 上手引导（manifest.guide，规范 v2.5）；null 时前端按描述/工具清单兜底 */
        private PluginService.PluginGuide guide;
        /** 被平台封禁时的原因；非空表示该插件已下架，界面应标红并禁止启用 */
        private String revokedReason;
        /** manifest.minHostVersion（规范 v2.7 P0）；null = 不限 */
        private String minHostVersion;
        /** 宿主版本低于 minHostVersion 时的原因文案；非空表示插件不生效，界面应提示升级客户端 */
        private String incompatibleReason;
        /** 是否本机 dev 免签直装（.awd-dev 标记）；实验 API（x- 前缀桥方法）只对它开放 */
        private boolean devInstalled;
        /** manifest.contributes（规范 v2.8 起）：广场展示与审查用，null = 无贡献内容 */
        private PluginService.Contributes contributes;
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        Map<String, Object> result = ok();
        result.put("disabled", revocationService.sync());
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> setEnabled(String pluginId, boolean enabled, String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            pluginService.setEnabled(pluginId, enabled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("插件不存在: ", "Plugin not found: ") + pluginId));
        } catch (IllegalStateException e) {
            // 被平台封禁的插件不允许重新启用
            return ResponseEntity.ok(error(e.getMessage()));
        }
        telemetryService.record("plugin.lifecycle",
                Map.of("pluginId", pluginId, "op", enabled ? "enable" : "disable"));
        return ResponseEntity.ok(ok());
    }

    /**
     * Web 面板直调本插件工具（规范 v2.5）：绕过模型，不绕过任何安全闸——
     * 登录会话 + 项目写权限 + 工具必须是该插件 manifest 声明的；
     * 之后走与 AI 链路同一个 {@link com.checkba.service.ai.ToolRegistry#execute}，
     * manifest 权限校验、宿主 SPI 配额、projectId/userId 以服务端为准的规则全部照旧。
     * 请求体：{"projectId": 123, "args": {...}}；args 里与 ToolContext 同名的参数会被服务端值覆盖。
     */
    @PostMapping("/{id}/tools/{tool}")
    public ResponseEntity<Map<String, Object>> invokeTool(
            @PathVariable("id") String pluginId,
            @PathVariable("tool") String toolName,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || !pluginService.isEnabled(pluginId) || pluginService.revokedReason(pluginId) != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("插件不存在或未启用: ", "Plugin not found or disabled: ") + pluginId));
        }
        boolean declared = meta.getTools() != null && meta.getTools().stream()
                .anyMatch(t -> toolName.equals(t.getName()));
        if (!declared) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("该插件未声明此工具: ", "Tool not declared by this plugin: ") + toolName));
        }
        Object pidRaw = body == null ? null : body.get("projectId");
        Long projectId = pidRaw instanceof Number n ? n.longValue() : null;
        if (projectId == null || !projectMemberService.hasWritePermission(projectId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("无权限访问该项目", "No access to this project")));
        }
        Object args = body.get("args");
        String argsJson;
        try {
            argsJson = args == null ? "{}" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error(LangText.of("args 不是合法 JSON 对象", "args is not a valid JSON object")));
        }
        ToolRegistry.ToolResult result = toolRegistry.execute(toolName,
                argsJson, new ToolContext(projectId, null, userId, null));
        Map<String, Object> out = new HashMap<>();
        out.put("code", result.found() ? 0 : 1);
        out.put("output", result.output());
        return ResponseEntity.ok(out);
    }

    /** ai.request 的 prompt+system 合计上限（字符）：面板内一次性辅助推理，不是对话通道 */
    static final int AI_REQUEST_MAX_CHARS = 16000;

    /**
     * Web 插件桥 ai.request 的服务端落点（规范 v2.7 P2）：插件经平台 Credits 通道调辅助模型，
     * 免带 Key——计费/配额/审计全在宿主。安全闸自上而下对齐 invokeTool：
     * 登录会话 → 插件启用未封禁 → manifest 声明 {@code ai} 权限（服务端是权威）→ 项目写权限
     * → 长度上限 → 每插件 10 次/分钟频控 → PlatformAiUserScope 里调辅助模型并记账。
     * 响应恒 200：{@code {code:0, text, modelId}} 或 {@code {code:1, error}}。
     */
    @PostMapping("/{id}/ai/complete")
    public ResponseEntity<Map<String, Object>> aiComplete(
            @PathVariable("id") String pluginId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || !pluginService.isEnabled(pluginId) || pluginService.revokedReason(pluginId) != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("插件不存在或未启用: ", "Plugin not found or disabled: ") + pluginId));
        }
        if (meta.getPermissions() == null || !meta.getPermissions().contains("ai")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(LangText.of("插件未声明 ai 权限", "Plugin does not declare the 'ai' permission")));
        }
        Object pidRaw = body == null ? null : body.get("projectId");
        Long projectId = pidRaw instanceof Number n ? n.longValue() : null;
        if (projectId == null || !projectMemberService.hasWritePermission(projectId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("无权限访问该项目", "No access to this project")));
        }
        String prompt = body.get("prompt") instanceof String s ? s : null;
        String system = body.get("system") instanceof String s ? s : null;
        String purpose = body.get("purpose") instanceof String s ? s : "";
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.ok(errorCoded("invalid_params", LangText.of("prompt 不能为空", "prompt is required")));
        }
        int total = prompt.length() + (system == null ? 0 : system.length());
        if (total > AI_REQUEST_MAX_CHARS) {
            return ResponseEntity.ok(errorCoded("quota_exceeded",
                    LangText.of("prompt+system 超过 " + AI_REQUEST_MAX_CHARS + " 字符上限",
                            "prompt+system exceeds the " + AI_REQUEST_MAX_CHARS + " character limit")));
        }
        try {
            pluginHostFactory.acquireAiQuota(pluginId);
        } catch (com.checkba.plugin.api.HostQuotaException e) {
            return ResponseEntity.ok(errorCoded("quota_exceeded", e.getMessage()));
        }
        try {
            String modelId = auxModelResolver.auxModelId();
            java.util.List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
            if (system != null && !system.isBlank()) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(system));
            }
            messages.add(dev.langchain4j.data.message.UserMessage.from(prompt));
            dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> r =
                    com.checkba.service.ai.PlatformAiUserScope.call(userId,
                            () -> chatModelFactory.getAuxChatModel().generate(messages));
            try {
                if (r.tokenUsage() != null) {
                    tokenUsageService.recordUsage(projectId, userId, modelId, r.tokenUsage(), null);
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(PluginController.class)
                        .warn("plugin {} ai.request usage record failed: {}", pluginId, e.getMessage());
            }
            org.slf4j.LoggerFactory.getLogger(PluginController.class)
                    .info("plugin {} ai.request purpose={} model={} tokens={}", pluginId, purpose, modelId, r.tokenUsage());
            Map<String, Object> out = ok();
            out.put("text", r.content() == null ? "" : r.content().text());
            out.put("modelId", modelId);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.ok(errorCoded("ai_failed", e.getMessage()));
        }
    }

    private static Map<String, Object> errorCoded(String errorCode, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("errorCode", errorCode);
        result.put("message", message);
        return result;
    }

    // ==================== 声明式贡献点（规范 v2.9 P4）====================

    /** 全部已启用插件贡献的文书模板（新建入口与 AI 工具面共用这份清单） */
    @GetMapping("/contributed/templates")
    public ResponseEntity<Map<String, Object>> listContributedTemplates(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        Map<String, Object> result = ok();
        result.put("templates", contributionService.listTemplates());
        return ResponseEntity.ok(result);
    }

    /** 从贡献模板创建项目文件：登录 + 项目写权限（与 invokeTool 同档） */
    @PostMapping("/contributed/templates/create")
    public ResponseEntity<Map<String, Object>> createFromTemplate(
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        Object pidRaw = body.get("projectId");
        Long projectId = pidRaw instanceof Number n ? n.longValue() : null;
        if (projectId == null || !projectMemberService.hasWritePermission(projectId, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("无权限访问该项目", "No access to this project")));
        }
        Object parentRaw = body.get("parentId");
        Long parentId = parentRaw instanceof Number n ? n.longValue() : null;
        try {
            var file = contributionService.createFromTemplate(projectId, userId,
                    String.valueOf(body.get("pluginId")), String.valueOf(body.get("templateId")),
                    parentId, body.get("name") == null ? null : String.valueOf(body.get("name")));
            Map<String, Object> result = ok();
            result.put("fileId", file.getId());
            result.put("name", file.getName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /** 已启用插件贡献的样式画像清单 + 当前选中项 */
    @GetMapping("/contributed/style-profiles")
    public ResponseEntity<Map<String, Object>> listContributedStyleProfiles(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        Map<String, Object> result = ok();
        result.put("profiles", contributionService.listStyleProfiles());
        return ResponseEntity.ok(result);
    }

    /** 选定/清除全局默认画像（admin，与启停同口径）；ref 形如 "<pluginId>:<profileId>"，空 = 清除 */
    @PostMapping("/contributed/style-profiles/select")
    public ResponseEntity<Map<String, Object>> selectContributedStyleProfile(
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            contributionService.selectStyleProfile(body == null ? null : body.get("ref"));
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /** 插件设置：声明 + 当前值（secret 只回显尾 4 位） */
    @GetMapping("/{id}/settings")
    public ResponseEntity<Map<String, Object>> getPluginSettings(
            @PathVariable("id") String pluginId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(LangText.of("请先登录", "Please sign in first")));
        }
        if (pluginService.getPlugin(pluginId) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("插件不存在: ", "Plugin not found: ") + pluginId));
        }
        Map<String, Object> result = ok();
        result.put("settings", contributionService.settingsView(pluginId));
        return ResponseEntity.ok(result);
    }

    /** 保存插件设置（admin，与启停同口径；按声明校验类型，未声明的键拒绝） */
    @PostMapping("/{id}/settings")
    public ResponseEntity<Map<String, Object>> savePluginSettings(
            @PathVariable("id") String pluginId,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> values,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            contributionService.saveSettings(pluginId, values);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
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
        view.setGuide(meta.getGuide());
        view.setEnabled(pluginService.isEnabled(meta.getId()));
        view.setRevokedReason(pluginService.revokedReason(meta.getId()));
        view.setMinHostVersion(meta.getMinHostVersion());
        view.setIncompatibleReason(pluginService.incompatibleReason(meta.getId()));
        view.setDevInstalled(pluginService.isDevInstalled(meta.getId()));
        view.setContributes(meta.getContributes());
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
