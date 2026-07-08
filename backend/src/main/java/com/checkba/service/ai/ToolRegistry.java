package com.checkba.service.ai;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolContext;
import com.checkba.service.ai.tools.ToolContextHolder;
import com.checkba.service.ai.tools.ToolMeta;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工具注册与分发层（能力层核心）。
 *
 * 职责：
 * 1. 启动时扫描所有 {@link AgentToolComponent} Bean 中的 @Tool 方法并注册；
 * 2. 运行时按需注册插件工具（PluginService 加载的 JAR 工具），使插件真正可被 Agent 调用；
 * 3. 按工具名分发调用：反射绑定参数、注入 {@link ToolContext}（projectId 等以服务端上下文为准，
 *    防止 LLM 伪造跨项目 ID）、容错转换 LLM 生成的参数值；
 * 4. 暴露工具元数据（{@link ToolMeta}）供编排层做展示名与文件副作用处理。
 *
 * 编排器（AgentOrchestrator）不再感知任何具体工具类——新增工具只需实现
 * AgentToolComponent 或作为插件放入 plugins/ 目录。
 */
@Service
@Slf4j
public class ToolRegistry {

    /** 强制从服务端上下文注入的参数名（LLM 传入值一律忽略） */
    private static final Set<String> SERVER_CONTEXT_PARAMS = Set.of("projectId", "conversationId", "userId");

    /** LLM 历史上使用过的参数别名（协议容错，保持向后兼容） */
    private static final Map<String, List<String>> ARG_ALIASES = Map.of(
            "fileName", List.of("name", "filename"),
            "markdownContent", List.of("markdown_content", "content"),
            "filePath", List.of("path"),
            "fileId", List.of("id")
    );

    /** 旧编排器为部分工具提供的缺省参数值（行为保持；键为别名解析后的真实工具名） */
    private static final Map<String, Object> LEGACY_DEFAULTS = Map.of(
            "doc_find_replace.replaceAll", Boolean.TRUE,
            "doc_find_text.matchCase", Boolean.FALSE,
            "doc_delete_text.deleteAll", Boolean.FALSE,
            "list_files.subPath", ".",
            "query_memory.type", "all",
            "doc_get_paragraph.paragraphIndex", 1,
            "doc_modify_paragraph.paragraphIndex", 1,
            "doc_replace_nth_match.matchIndex", 1,
            "doc_delete_match.matchIndex", 1
    );

    /**
     * 工具名别名（旧 prompt / 老对话历史 / 模型惯性输出中出现过的名称映射到真实工具）。
     *
     * wps_* → doc_* 是 Phase 2.5 灰度更名（编辑器已从 WPS 迁移到 LibreOffice，
     * LLM 面工具名同步去 WPS 化）的兜底：老对话历史、老 prompt、模型按惯性输出的
     * 旧名仍能正确分发。该批别名至少保留两个发布版本（≥0.6.0）后再评估移除。
     */
    public static final Map<String, String> TOOL_NAME_ALIASES = Map.ofEntries(
            Map.entry("search_laws", "search_web"),
            // ---- Phase 2.5：WPS 时代旧工具名 → doc_*（since 0.4.x，移除不早于 0.6.0）----
            Map.entry("wps_list_project_files", "doc_list_project_files"),
            Map.entry("wps_open_file", "doc_open_file"),
            Map.entry("wps_start_stream", "doc_start_stream"),
            Map.entry("wps_get_selection", "doc_get_selection"),
            Map.entry("wps_goto", "doc_goto"),
            Map.entry("wps_set_selection", "doc_set_selection"),
            Map.entry("wps_find_text", "doc_find_text"),
            Map.entry("wps_find_replace", "doc_find_replace"),
            Map.entry("wps_replace_nth_match", "doc_replace_nth_match"),
            Map.entry("wps_delete_match", "doc_delete_match"),
            Map.entry("wps_delete_text", "doc_delete_text"),
            Map.entry("wps_replace_selection", "doc_replace_selection"),
            Map.entry("wps_insert_at_cursor", "doc_insert_at_cursor"),
            Map.entry("wps_get_paragraph", "doc_get_paragraph"),
            Map.entry("wps_modify_paragraph", "doc_modify_paragraph"),
            Map.entry("wps_get_outline", "doc_get_outline"),
            Map.entry("wps_insert_under_heading", "doc_insert_under_heading"),
            Map.entry("wps_search_related_docs", "doc_search_related_docs"),
            Map.entry("wps_get_document_text", "doc_get_document_text"),
            Map.entry("wps_get_cursor_context", "doc_get_cursor_context"),
            Map.entry("wps_select_anchor", "doc_select_anchor"),
            Map.entry("wps_select_paragraph", "doc_select_paragraph"),
            Map.entry("wps_collapse_cursor", "doc_collapse_cursor"),
            Map.entry("wps_replace_at_anchor", "doc_replace_at_anchor"),
            Map.entry("wps_delete_selection", "doc_delete_selection"),
            Map.entry("wps_format_selection", "doc_format_selection"),
            Map.entry("wps_set_paragraph_format", "doc_set_paragraph_format"),
            Map.entry("wps_undo", "doc_undo"),
            Map.entry("wps_redo", "doc_redo"),
            Map.entry("wps_debug_revisions", "doc_debug_revisions")
    );

    /**
     * 一个已注册工具：宿主 Bean + 方法 + LLM 规格 + 产品元数据。
     */
    public record RegisteredTool(Object bean, Method method, ToolSpecification spec, ToolMeta meta, boolean fromPlugin) {

        public String displayName() {
            if (meta != null && !meta.displayName().isEmpty()) {
                return meta.displayName();
            }
            return "工具执行";
        }
    }

    /**
     * 一次工具分发的结果。found=false 表示注册表中没有该工具。
     */
    public record ToolResult(String output, RegisteredTool tool, boolean found) {

        public boolean success() {
            return found && output != null && !output.startsWith("Error");
        }
    }

    private final List<AgentToolComponent> toolComponents;
    private final PluginService pluginService;

    private final Map<String, RegisteredTool> builtinTools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredTool> pluginToolCache = new ConcurrentHashMap<>();
    private final List<ToolSpecification> builtinSpecifications = new ArrayList<>();

    public ToolRegistry(List<AgentToolComponent> toolComponents, PluginService pluginService) {
        this.toolComponents = toolComponents;
        this.pluginService = pluginService;
    }

    @PostConstruct
    public void init() {
        for (AgentToolComponent bean : toolComponents) {
            registerBean(bean);
        }
        log.info("ToolRegistry initialized: {} built-in tools from {} components",
                builtinTools.size(), toolComponents.size());
    }

    private void registerBean(Object bean) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            try {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                ToolMeta meta = method.getAnnotation(ToolMeta.class);
                RegisteredTool previous = builtinTools.put(spec.name(),
                        new RegisteredTool(bean, method, spec, meta, false));
                if (previous != null) {
                    log.warn("Duplicate tool name '{}' — {} overrides {}",
                            spec.name(), bean.getClass().getSimpleName(),
                            previous.bean().getClass().getSimpleName());
                } else {
                    builtinSpecifications.add(spec);
                }
            } catch (Exception e) {
                log.error("Failed to register tool method {}.{}: {}",
                        bean.getClass().getSimpleName(), method.getName(), e.getMessage());
            }
        }
    }

    /**
     * 全部工具规格（内置 + 已启用插件），传给 LLM 做原生 function calling。
     * 禁用插件的工具规格不下发——LLM 看不到即不会调用。
     */
    public List<ToolSpecification> getAllSpecifications() {
        List<ToolSpecification> all = new ArrayList<>(builtinSpecifications);
        for (ToolSpecification spec : pluginService.getToolSpecifications()) {
            if (pluginToolEnabled(spec.name())) {
                all.add(spec);
            }
        }
        return all;
    }

    /**
     * 插件启停过滤：内置工具（不属于任何插件，pid == null）恒可见；
     * 插件工具仅在所属插件启用时可见。
     */
    private boolean pluginToolEnabled(String toolName) {
        String pid = pluginService.getPluginIdForTool(toolName);
        return pid == null || pluginService.isEnabled(pid);
    }

    public boolean hasTool(String name) {
        return resolve(name).isPresent();
    }

    /**
     * 已注册工具名，按长度降序（供 XML 协议解析做最长名优先匹配，
     * 避免 pptx_generate 误匹配 pptx_generate_outline 的调用）。
     */
    public List<String> toolNamesLongestFirst() {
        List<String> names = new ArrayList<>(builtinTools.keySet());
        for (String name : pluginService.getPluginTools().keySet()) {
            if (pluginToolEnabled(name)) {
                names.add(name);
            }
        }
        names.sort(Comparator.comparingInt(String::length).reversed());
        return names;
    }

    public Optional<RegisteredTool> resolve(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        RegisteredTool tool = builtinTools.get(name);
        if (tool != null) {
            return Optional.of(tool);
        }
        // 插件工具：先过启停过滤（禁用插件的工具视同不存在，重新启用即恢复）
        if (!pluginToolEnabled(name)) {
            return Optional.empty();
        }
        // 懒注册（插件可能在运行期热加载）
        RegisteredTool cached = pluginToolCache.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        Object pluginBean = pluginService.getPluginTools().get(name);
        if (pluginBean != null) {
            for (Method method : pluginBean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class) && method.getName().equals(name)) {
                    try {
                        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                        RegisteredTool registered = new RegisteredTool(
                                pluginBean, method, spec, method.getAnnotation(ToolMeta.class), true);
                        pluginToolCache.put(name, registered);
                        return Optional.of(registered);
                    } catch (Exception e) {
                        log.error("Failed to resolve plugin tool {}: {}", name, e.getMessage());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 分发一次工具调用。
     *
     * @param name     工具名（支持 TOOL_NAME_ALIASES 别名）
     * @param argsJson LLM 生成的 JSON 参数
     * @param ctx      服务端上下文（覆盖同名参数）
     */
    public ToolResult execute(String name, String argsJson, ToolContext ctx) {
        String resolvedName = TOOL_NAME_ALIASES.getOrDefault(name, name);
        Optional<RegisteredTool> toolOpt = resolve(resolvedName);
        if (toolOpt.isEmpty()) {
            return new ToolResult("Tool not found or arguments invalid.", null, false);
        }
        RegisteredTool tool = toolOpt.get();

        // 插件工具权限校验（规范 v2）：所需权限未在 manifest permissions 中声明即拒绝
        if (tool.fromPlugin()) {
            List<String> missing = pluginService.missingPermissionsForTool(resolvedName);
            if (!missing.isEmpty()) {
                log.warn("Tool '{}' rejected: requires undeclared permission(s) {}", resolvedName, missing);
                return new ToolResult("Error: permission denied — tool '" + resolvedName
                        + "' requires permission(s) " + missing
                        + " not declared in the plugin manifest \"permissions\".", tool, true);
            }
        }

        // 装填线程上下文：修复流式回调线程与请求线程不一致导致的 ThreadLocal 丢失/串会话问题
        ToolContextHolder.set(ctx);
        if (ctx != null) {
            if (ctx.projectId() != null) {
                ProjectContextHolder.setProjectId(String.valueOf(ctx.projectId()));
            }
            if (ctx.conversationId() != null) {
                ProjectContextHolder.setConversationId(ctx.conversationId());
            }
            if (ctx.userId() != null) {
                ProjectContextHolder.setUserId(ctx.userId());
            }
        }

        try {
            cn.hutool.json.JSONObject args = parseArgs(argsJson);
            Object[] boundArgs = bindArguments(resolvedName, tool.method(), args, ctx);
            Object result = tool.method().invoke(tool.bean(), boundArgs);
            return new ToolResult(result != null ? result.toString() : "", tool, true);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool '{}' execution failed", resolvedName, cause);
            return new ToolResult("Error executing tool: " + cause.getMessage(), tool, true);
        } catch (Exception e) {
            log.error("Tool '{}' dispatch failed", resolvedName, e);
            return new ToolResult("Error executing tool: " + e.getMessage(), tool, true);
        } finally {
            ToolContextHolder.clear();
            // 同时清理 ProjectContextHolder：装填时设置了它（见上），此前只清 ToolContextHolder，
            // 池化回调线程复用会残留上个会话的 projectId/userId，导致记忆作用域串号。
            ProjectContextHolder.clear();
        }
    }

    private cn.hutool.json.JSONObject parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return new cn.hutool.json.JSONObject();
        }
        try {
            return cn.hutool.json.JSONUtil.parseObj(argsJson);
        } catch (Exception e) {
            log.warn("Failed to parse tool args as JSON, using empty args: {}",
                    argsJson.length() > 200 ? argsJson.substring(0, 200) + "..." : argsJson);
            return new cn.hutool.json.JSONObject();
        }
    }

    private Object[] bindArguments(String toolName, Method method, cn.hutool.json.JSONObject args, ToolContext ctx) {
        Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String paramName = p.getName();
            Object value;

            if (SERVER_CONTEXT_PARAMS.contains(paramName)) {
                value = fromContext(paramName, ctx, p.getType());
            } else if ("modelId".equals(paramName)) {
                // modelId：优先 LLM 显式指定，缺省回落到会话所选模型
                Object raw = rawArg(args, paramName);
                value = convert(raw, p.getType());
                if (value == null && ctx != null) {
                    value = ctx.modelId();
                }
            } else {
                Object raw = rawArg(args, paramName);
                if (raw == null) {
                    raw = LEGACY_DEFAULTS.get(toolName + "." + paramName);
                }
                value = convert(raw, p.getType());
            }

            if (value == null && p.getType().isPrimitive()) {
                value = primitiveDefault(p.getType());
            }
            values[i] = value;
        }
        return values;
    }

    /** 按参数名取值，值为空串时尝试历史别名 */
    private Object rawArg(cn.hutool.json.JSONObject args, String paramName) {
        Object raw = args.get(paramName);
        if (isBlank(raw)) {
            for (String alias : ARG_ALIASES.getOrDefault(paramName, List.of())) {
                Object aliased = args.get(alias);
                if (!isBlank(aliased)) {
                    return aliased;
                }
            }
            return isBlank(raw) ? null : raw;
        }
        return raw;
    }

    private boolean isBlank(Object v) {
        return v == null || (v instanceof String s && s.isEmpty());
    }

    private Object fromContext(String paramName, ToolContext ctx, Class<?> targetType) {
        if (ctx == null) {
            return null;
        }
        Object value = switch (paramName) {
            case "projectId" -> ctx.projectId();
            case "conversationId" -> ctx.conversationId();
            case "userId" -> ctx.userId();
            default -> null;
        };
        return convert(value, targetType);
    }

    /**
     * 容错类型转换：处理 LLM 生成的 "null"/"None" 字符串、数字字符串、字符串布尔值等。
     * 转换失败返回 null（与旧 safeParseXxx 行为一致：记录日志、不抛异常）。
     */
    Object convert(Object raw, Class<?> targetType) {
        if (raw == null) {
            return null;
        }
        // LLM 常把缺省参数写成字符串 "null"/"None"——对非字符串目标统一归一为 null
        if (raw instanceof String s && targetType != String.class) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "none".equalsIgnoreCase(trimmed)) {
                return null;
            }
        }
        try {
            if (targetType == String.class) {
                return raw instanceof String ? raw : String.valueOf(raw);
            }
            if (targetType == Long.class || targetType == long.class) {
                return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
            }
            if (targetType == Integer.class || targetType == int.class) {
                return raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
            }
            if (targetType == Double.class || targetType == double.class) {
                return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return raw instanceof Boolean b ? b : "true".equalsIgnoreCase(raw.toString().trim());
            }
            if (targetType == Map.class) {
                if (raw instanceof cn.hutool.json.JSONObject obj) {
                    return obj.toBean(Map.class);
                }
                return cn.hutool.json.JSONUtil.parseObj(raw.toString()).toBean(Map.class);
            }
            return raw;
        } catch (Exception e) {
            log.warn("Failed to convert tool arg '{}' to {}", raw, targetType.getSimpleName());
            return null;
        }
    }

    private Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        return null;
    }

    /**
     * 历史参数别名表（供 XML 协议解析器共用）。
     */
    public static List<String> aliasesFor(String paramName) {
        return ARG_ALIASES.getOrDefault(paramName, List.of());
    }
}
