package com.checkba.service.telemetry;

import java.util.Map;
import java.util.Set;

/**
 * 埋点字段白名单：隐私红线的强制执行层（设计 §3、§5.3）。
 *
 * 只放行「枚举语义的短字符串 / 数值 / 布尔」。任何白名单外字段一律剔除；
 * 未知事件名整条拒绝。文件名、路径、项目名、消息文本、原始 conversationId
 * 等用户内容没有对应白名单条目，从结构上无法进入账本与上报。
 *
 * 新增事件或字段必须同步：本类 + TelemetryServiceTest + 官网仓 events 端点白名单。
 */
public final class TelemetryAttrWhitelist {

    private TelemetryAttrWhitelist() {}

    /** 字符串字段值长度上限：枚举语义不需要更长；顺带兜底防止内容误入 */
    static final int MAX_STRING_LEN = 64;

    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("app.start", Set.of("platform", "profile")),
            Map.entry("ai.turn", Set.of("mode", "model", "provider", "outcome",
                    "durationMs", "attachmentCount", "hasPinnedSkill")),
            Map.entry("ai.tool", Set.of("toolName", "success", "durationMs",
                    "fileEffect", "fromPlugin")),
            Map.entry("ai.model", Set.of("provider", "targetModel", "streaming")),
            Map.entry("editor.action", Set.of("action", "agent", "success",
                    "durationMs", "whitelistRejected")),
            Map.entry("editor.bridge", Set.of("action", "outcome", "durationMs")),
            Map.entry("skill.activated", Set.of("skillId", "how")),
            Map.entry("skill.lifecycle", Set.of("skillId", "op")),
            Map.entry("plugin.lifecycle", Set.of("pluginId", "op")),
            Map.entry("project.created", Set.of("kind", "reused", "importedCount")),
            Map.entry("file.changed", Set.of()),
            Map.entry("version.op", Set.of("op", "ok")),
            Map.entry("ui.nav", Set.of("page", "panelKey", "branch")),
            Map.entry("matter.classified", Set.of("category", "source"))
    );

    /** 未知事件名 → null（整条拒绝）；已知事件 → 允许的字段集 */
    static Set<String> allowedAttrs(String eventName) {
        return ALLOWED.get(eventName);
    }

    static boolean isAllowedValue(Object v) {
        if (v instanceof Boolean || v instanceof Number) return true;
        if (v instanceof String s) return s.length() <= MAX_STRING_LEN;
        return false;
    }
}
