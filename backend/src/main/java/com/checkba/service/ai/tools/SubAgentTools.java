package com.checkba.service.ai.tools;

import com.checkba.service.ai.subagent.SubAgentResult;
import com.checkba.service.ai.subagent.SubAgentService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 委派子任务工具（Phase 3C 多智能体协作第一阶段）。
 *
 * 把一个自包含的复杂子问题交给独立的子 Agent 循环执行，
 * 子 Agent 的中间过程不进入主会话历史，只返回最终结构化结果。
 * 身份字段（projectId/conversationId/userId）从 {@link ToolContextHolder}
 * 继承主会话上下文（不变式 3），LLM 无法伪造。
 */
@Component
public class SubAgentTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SubAgentTools.class);

    private final SubAgentService subAgentService;

    public SubAgentTools(SubAgentService subAgentService) {
        this.subAgentService = subAgentService;
    }

    @ToolMeta(displayName = "委派子任务", category = "agent")
    @Tool("Delegate a self-contained subtask to an independent sub-agent that runs its own tool-use loop "
            + "and returns only the final structured result (JSON: success/result/error/toolsUsed/rounds). "
            + "Use ONLY for complex sub-problems that need independent multi-step exploration or produce lots "
            + "of intermediate output. NEVER delegate simple tasks you can do directly with one or two tool calls. "
            + "task_description: complete standalone description of the subtask (the sub-agent cannot see this "
            + "conversation). expected_output: precise description of what the result must contain. "
            + "tool_scope: tool names the sub-agent may use, as a JSON array or comma-separated string "
            + "(empty = all tools). dispatch_subtask itself is never available to the sub-agent.")
    public String dispatch_subtask(String task_description, String expected_output, String tool_scope) {
        log.info("Tool: dispatch_subtask called, scope='{}'", tool_scope);
        if (SubAgentService.inSubAgent()) {
            // 防递归第二道防线（第一道在 SubAgentService 的分发拦截）
            return "Error: dispatch_subtask is not available inside a sub-agent (nested delegation refused).";
        }
        if (task_description == null || task_description.isBlank()) {
            return "Error: task_description is required.";
        }
        ToolContext ctx = ToolContextHolder.get();
        SubAgentResult result = subAgentService.dispatch(
                task_description, expected_output, parseToolScope(tool_scope), ctx);
        return result.toJson();
    }

    /**
     * 容错解析 tool_scope：JSON 数组（["a","b"]）或逗号/顿号分隔字符串（"a, b"）均可。
     */
    public static List<String> parseToolScope(String toolScope) {
        List<String> names = new ArrayList<>();
        if (toolScope == null || toolScope.isBlank()) {
            return names;
        }
        String trimmed = toolScope.trim();
        if (trimmed.startsWith("[")) {
            try {
                for (Object item : cn.hutool.json.JSONUtil.parseArray(trimmed)) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        names.add(String.valueOf(item).trim());
                    }
                }
                return names;
            } catch (Exception ignored) {
                // 非法 JSON 数组，退回分隔符解析
            }
        }
        for (String part : trimmed.split("[,，、;\\s]+")) {
            String name = part.trim()
                    .replaceAll("^[\\[\"']+", "")
                    .replaceAll("[\\]\"']+$", "");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
