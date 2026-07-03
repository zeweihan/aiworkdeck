package com.checkba.service.ai.eval;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记录型 ToolRegistry：注册真实工具（真实工具名/参数名/别名），
 * 但 execute 只记录分发序列并返回桩输出，从不真正执行工具方法。
 *
 * 这样断言的是「编排器 → 注册表」这条边界上的行为：
 * 给定回放的模型输出，编排器应当分发出怎样的 (toolName, argsJson) 序列。
 */
public class RecordingToolRegistry extends ToolRegistry {

    /** 一次分发记录。resolvedName 是别名解析后的工具名（如 search_laws -> search_web） */
    public record Dispatch(String rawName, String resolvedName, String argsJson) {
    }

    private final List<Dispatch> dispatches = new ArrayList<>();
    private Map<String, String> stubs = Map.of();

    public RecordingToolRegistry(List<AgentToolComponent> components, PluginService pluginService) {
        super(components, pluginService);
    }

    /** 设置工具桩输出（key = 别名解析后的工具名） */
    public void setStubs(Map<String, String> stubs) {
        this.stubs = stubs == null ? Map.of() : stubs;
    }

    public List<Dispatch> dispatches() {
        return List.copyOf(dispatches);
    }

    @Override
    public ToolResult execute(String name, String argsJson, ToolContext ctx) {
        String resolved = TOOL_NAME_ALIASES.getOrDefault(name, name);
        dispatches.add(new Dispatch(name, resolved, argsJson));
        Optional<RegisteredTool> tool = resolve(resolved);
        if (tool.isEmpty()) {
            // 与生产行为一致：未注册工具返回 found=false
            return new ToolResult("Tool not found or arguments invalid.", null, false);
        }
        String output = stubs.getOrDefault(resolved, "OK (eval stub)");
        return new ToolResult(output, tool.get(), true);
    }
}
