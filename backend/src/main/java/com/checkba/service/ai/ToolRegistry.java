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
     * 历史：Phase 2.5 灰度更名期间这里曾有 wps_* → doc_* 全量别名（since 0.4.x），
     * 约定 ≥0.6.0 后移除，已于 0.7.9 后清理。旧名不再分发：模型输出 wps_* 会收到
     * "未知工具"反馈并按系统提示改用 doc_*。
     */
    public static final Map<String, String> TOOL_NAME_ALIASES = Map.ofEntries(
            Map.entry("search_laws", "search_web")
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
            if (!found || output == null) return false;
            String trimmed = output.stripLeading();
            if (trimmed.startsWith("Error")) return false;
            // 编辑器桥等工具的失败以 JSON 返回（如 {"error": "操作超时..."}）。
            // 此前只认 "Error" 前缀，这类失败被判成 SUCCESS：失败熔断计数被清零、
            // 前端显示绿勾、file_change 照发——模型一路"成功"空转到步数上限（F-09）。
            String compact = trimmed.replace(" ", "").replace("\t", "");
            return !compact.startsWith("{\"error\"");
        }
    }

    private final List<AgentToolComponent> toolComponents;
    private final PluginService pluginService;
    private final ClientCapabilityService clientCapabilityService;

    private final Map<String, RegisteredTool> builtinTools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredTool> pluginToolCache = new ConcurrentHashMap<>();
    private final List<ToolSpecification> builtinSpecifications = new ArrayList<>();

    public ToolRegistry(List<AgentToolComponent> toolComponents, PluginService pluginService,
                        ClientCapabilityService clientCapabilityService) {
        this.toolComponents = toolComponents;
        this.pluginService = pluginService;
        this.clientCapabilityService = clientCapabilityService;
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
     * 按会话客户端能力过滤后的全部工具规格（Phase C）：
     * office 会话隐藏 doc_* 与 sheet_*（LOWA 专属远端执行工具），LOWA 会话隐藏 office_*，
     * none 会话两者都隐藏。conversationId 为 null 时按默认能力（LOWA）处理。
     */
    public List<ToolSpecification> getAllSpecifications(String conversationId) {
        List<ToolSpecification> filtered = new ArrayList<>();
        for (ToolSpecification spec : getAllSpecifications()) {
            if (clientCapabilityService.isToolVisible(spec.name(), conversationId)) {
                filtered.add(spec);
            }
        }
        return filtered;
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

    /**
     * 会话能力感知的 resolve（Phase C）：能力档位下不可见的工具视同不存在。
     * 无会话语境的元数据查询（展示名等）仍可用单参 {@link #resolve(String)}。
     */
    public Optional<RegisteredTool> resolve(String name, String conversationId) {
        if (name != null && !clientCapabilityService.isToolVisible(name, conversationId)) {
            return Optional.empty();
        }
        return resolve(name);
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
        // 会话能力过滤：能力档位下不可见的工具按"不存在"拒绝（模型收到与未知工具一致的反馈）
        Optional<RegisteredTool> toolOpt = resolve(resolvedName, ctx != null ? ctx.conversationId() : null);
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
            cn.hutool.json.JSONObject args;
            try {
                args = parseArgs(argsJson);
            } catch (IllegalArgumentException badArgs) {
                // 参数 JSON 解析失败不能静默降级成空参执行（F-16）：工具会拿着一堆
                // null/默认值跑出无意义结果甚至误改文档。返回可行动错误让模型自纠。
                return new ToolResult("Error: tool arguments are not valid JSON. "
                        + "Please re-emit the call to '" + resolvedName
                        + "' with a well-formed JSON object of named arguments. Parse error: "
                        + badArgs.getMessage(), tool, true);
            }
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
            log.warn("Failed to parse tool args as JSON: {}",
                    argsJson.length() > 200 ? argsJson.substring(0, 200) + "..." : argsJson);
            // 交给 execute() 转成可行动的工具错误回喂模型，而不是空参硬跑（F-16）
            throw new IllegalArgumentException(e.getMessage(), e);
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

    /**
     * 是否为服务端注入参数（对 LLM 不可见，XML 解析器做位置参数映射时须跳过）。
     */
    public static boolean isServerContextParam(String paramName) {
        return SERVER_CONTEXT_PARAMS.contains(paramName);
    }
}
