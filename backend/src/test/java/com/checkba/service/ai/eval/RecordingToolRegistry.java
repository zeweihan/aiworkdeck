// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /**
     * 一次分发记录。resolvedName 是别名解析后的工具名（别名表现已为空，两者通常相同）。
     *
     * @param found 这次分发有没有解析到工具。false = 模型调了一个本会话里不存在或不可见的工具，
     *              拿回的是 {@link ToolRegistry#unknownToolMessage} 那句——白烧一整轮
     *              （dev-board#809 实测「发现 A」的那个形态）。
     */
    public record Dispatch(String rawName, String resolvedName, String argsJson, boolean found) {
    }

    private final List<Dispatch> dispatches = new ArrayList<>();
    private Map<String, String> stubs = Map.of();
    private final com.checkba.service.ai.ClientCapabilityService capabilities;

    public RecordingToolRegistry(List<AgentToolComponent> components, PluginService pluginService) {
        // 评测里不声明 clientCapability：默认 LOWA 能力（与存量主前端一致），office_* 不下发
        this(components, pluginService, new com.checkba.service.ai.ClientCapabilityService());
    }

    private RecordingToolRegistry(List<AgentToolComponent> components, PluginService pluginService,
                                  com.checkba.service.ai.ClientCapabilityService capabilities) {
        super(components, pluginService, capabilities);
        this.capabilities = capabilities;
    }

    /**
     * 本注册表用的那一个能力登记簿。要登记别的会话能力（Office / none）时得拿到它——
     * init() 会把工具的 ToolMeta.requiresHost 声明推进去，另外 new 一个的话
     * 那些声明就不在里面（dev-board#799）。
     */
    public com.checkba.service.ai.ClientCapabilityService capabilities() {
        return capabilities;
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
        // 会话能力过滤必须和生产走同一条：生产的 ToolRegistry.execute 用的是
        // resolve(name, conversationId)，能力档下不可见的工具按「不存在」拒绝。
        // 这里原来调的是单参 resolve(name)，把那一层闸整个跳过了——于是
        // 「none 会话调 doc_list_project_files」在回放里会拿到桩输出 OK，
        // 生产里拿到的却是 "Tool not found or arguments invalid."，
        // 正是 dev-board#809 要治的那个白烧一轮（dev-board#809 / K29）。
        Optional<RegisteredTool> tool = resolve(resolved, ctx != null ? ctx.conversationId() : null);
        dispatches.add(new Dispatch(name, resolved, argsJson, tool.isPresent()));
        if (tool.isEmpty()) {
            // 与生产行为一致：未注册 / 本会话不可见的工具返回 found=false，
            // 并带上那句指路（审计 A11）
            return new ToolResult(ToolRegistry.unknownToolMessage(resolved), null, false);
        }
        String output = stubs.getOrDefault(resolved, "OK (eval stub)");
        return new ToolResult(output, tool.get(), true);
    }
}
