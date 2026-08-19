package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillMarketService;
import com.checkba.service.ai.skill.SkillRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 管理接口（规范见 docs/SKILL_SPEC.md，鉴权模式与 PluginController 一致）：
 * - GET  /list                    Skill 列表（含触发词、工具白名单、生效方式），登录即可查看
 * - POST /{id}/enable             启用 skill（仅 admin）
 * - POST /{id}/disable            禁用 skill（仅 admin）
 * - POST /{id}/activation {mode}  设置生效方式 auto/manual/disabled（仅 admin）
 * - POST /rescan                  重新扫描 skills/ 目录与插件携带的 skill（仅 admin）
 * - GET  /market/list             在线 Skill 广场列表，登录即可查看
 * - POST /market/install {id}     安装/重装在线 skill（仅 admin，重装即更新）
 * - POST /market/uninstall {id}   卸载在线安装的 skill（仅 admin）
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final SkillMarketService skillMarketService;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    private final com.checkba.service.pack.NativePackService nativePackService;

    @lombok.Data
    public static class SkillView {
        private String id;
        private String name;
        /** 英文展示名（skill.yml: name_en），英文界面下前端优先用它；缺省回退 name */
        private String nameEn;
        private String description;
        private List<String> triggers;
        private List<String> allowedTools;
        private String sourcePluginId;
        private boolean enabled;
        /** 生效方式：auto（命中触发词自动生效）/ manual（只能钉选）/ disabled */
        private String activationMode;
        private String author;
        private String authorUrl;
        private String version;
        private String license;
        /** 随 skill 分发的第三方内容署名（如 vendor 引擎），前端「详细信息」区原样展示 */
        private List<String> credits;
        /**
         * 当前是否真的能生效（SkillRegistry.isAvailable：未停用 + 所属插件启用 + 当前应用语言可用）。
         *
         * <p>本列表刻意<b>不做</b>语言过滤（管理面要能看到全部已安装的 skill），
         * 于是对话面板里那个「主动选择 Skill」的选择器必须靠这个字段自己滤一道——
         * 否则英文界面下用户能勾中一个 zh-only 的 skill，勾了却永远不生效，也没有任何提示。
         */
        private boolean available;
        /** 依赖的原生资源包 id（skill.yml: requires_pack）；不依赖资源包时为 null */
        private String packId;
        /**
         * 资源是否已就位。**无 requires_pack 时恒 true**；声明了的话 =
         * 「pack 已装」或「随包内置资源在场」（老版本用户的随包资源仍在，不该被逼着重下）。
         * 随列表一起给，省得前端为每行再打一次 /api/packs/{id}/status。
         */
        private boolean packReady = true;
    }

    @GetMapping("/list")
    public List<SkillView> listSkills() {
        return skillRegistry.getSkills().stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableSkill(
            @PathVariable("id") String skillId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return setEnabled(skillId, true, sessionId);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableSkill(
            @PathVariable("id") String skillId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return setEnabled(skillId, false, sessionId);
    }

    /** 设置生效方式（三档）：body {"mode": "auto"|"manual"|"disabled"} */
    @PostMapping("/{id}/activation")
    public ResponseEntity<Map<String, Object>> setActivation(
            @PathVariable("id") String skillId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        String raw = body == null ? null : body.get("mode");
        SkillRegistry.ActivationMode mode = SkillRegistry.ActivationMode.parse(raw).orElse(null);
        if (mode == null) {
            return ResponseEntity.badRequest().body(error(LangText.of("生效方式无效: ", "Invalid activation mode: ") + raw));
        }
        try {
            skillRegistry.setActivationMode(skillId, mode);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("Skill 不存在: ", "Skill not found: ") + skillId));
        }
        return ResponseEntity.ok(ok());
    }

    @PostMapping("/rescan")
    public ResponseEntity<Map<String, Object>> rescan(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        skillRegistry.rescan();
        Map<String, Object> result = ok();
        result.put("skillCount", skillRegistry.getSkills().size());
        return ResponseEntity.ok(result);
    }

    /**
     * 在线广场列表；注册表不可达返回 {code:1, message}，不影响本地区块。
     *
     * 要求登录：响应里带 purchased / accountConnected，属账户隐私，团队服务器部署下不能匿名可读
     * （与 EntitlementController 同口径——同一份「买了什么」不能一边设防一边敞开）。
     */
    @GetMapping("/market/list")
    public ResponseEntity<Map<String, Object>> listMarket(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("未登录"));
        }
        try {
            Map<String, Object> result = ok();
            result.put("skills", skillMarketService.listMarket());
            // 付费项按钮形态取决于账户是否已连接（未连接显示「需连接账户」），随列表一起给，
            // 省得前端为一个布尔再打一次 /api/account/status
            result.put("accountConnected", skillMarketService.accountConnected());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/market/install")
    public ResponseEntity<Map<String, Object>> installMarketSkill(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            String id = skillMarketService.install(body == null ? null : body.get("id"));
            Map<String, Object> result = ok();
            result.put("id", id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/market/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallMarketSkill(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            skillMarketService.uninstall(body == null ? null : body.get("id"));
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> setEnabled(String skillId, boolean enabled, String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            skillRegistry.setEnabled(skillId, enabled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(LangText.of("Skill 不存在: ", "Skill not found: ") + skillId));
        }
        telemetryService.record("skill.lifecycle",
                Map.of("skillId", skillId, "op", enabled ? "enable" : "disable"));
        return ResponseEntity.ok(ok());
    }

    private SkillView toView(SkillDefinition skill) {
        SkillView view = new SkillView();
        view.setId(skill.getId());
        view.setName(skill.getName());
        view.setNameEn(skill.getNameEn());
        view.setDescription(skill.getDescription());
        view.setTriggers(skill.getTriggers());
        view.setAllowedTools(skill.getAllowedTools());
        view.setSourcePluginId(skill.getSourcePluginId());
        view.setEnabled(skillRegistry.isEnabled(skill.getId()));
        view.setActivationMode(skillRegistry.activationMode(skill.getId()).name().toLowerCase());
        view.setAuthor(skill.getAuthor());
        view.setAuthorUrl(skill.getAuthorUrl());
        view.setVersion(skill.getVersion());
        view.setLicense(skill.getLicense());
        view.setCredits(skill.getCredits());
        view.setAvailable(skillRegistry.isAvailable(skill));
        String packId = skill.getRequiresPack();
        if (packId != null && !packId.isBlank()) {
            view.setPackId(packId);
            view.setPackReady(nativePackService.resourceReady(packId));
        }
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
