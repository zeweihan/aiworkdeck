package com.checkba.service.ai.subagent;

import java.util.List;

/**
 * 子任务的最终结构化结果——子 Agent 的中间过程（消息栈、工具输出）
 * 不写入主对话历史，只有本结果作为 dispatch_subtask 的工具输出返回给主循环。
 *
 * @param subtaskId 子任务 ID（subtask_progress SSE 事件里的 taskId 与此一致）
 * @param success   是否成功产出最终答案
 * @param result    成功时的结果文本（失败时为 null）
 * @param error     失败原因（成功时为 null）
 * @param toolsUsed 子 Agent 实际调用过的工具名（别名解析后，按调用顺序）
 * @param rounds    实际消耗的循环轮数
 */
public record SubAgentResult(
        String subtaskId,
        boolean success,
        String result,
        String error,
        List<String> toolsUsed,
        int rounds
) {

    public static SubAgentResult success(String subtaskId, String result, List<String> toolsUsed, int rounds) {
        return new SubAgentResult(subtaskId, true, result, null, List.copyOf(toolsUsed), rounds);
    }

    public static SubAgentResult failure(String subtaskId, String error, List<String> toolsUsed, int rounds) {
        return new SubAgentResult(subtaskId, false, null, error, List.copyOf(toolsUsed), rounds);
    }

    /** 序列化为返回给主循环 LLM 的 JSON 工具输出 */
    public String toJson() {
        cn.hutool.json.JSONObject o = new cn.hutool.json.JSONObject();
        o.set("subtaskId", subtaskId);
        o.set("success", success);
        if (result != null) {
            o.set("result", result);
        }
        if (error != null) {
            o.set("error", error);
        }
        o.set("toolsUsed", toolsUsed);
        o.set("rounds", rounds);
        return o.toString();
    }
}
