// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

/**
 * 单次工具调用的运行时上下文。
 * 由编排层在分发工具前构造，经 ToolRegistry 注入：
 * - 与工具方法同名参数（projectId/conversationId/userId）强制以此为准，防止 LLM 伪造跨项目 ID；
 * - modelId 优先取 LLM 显式传参，缺省时回落到此上下文；
 * - 同时通过 {@link ToolContextHolder} 暴露给工具内部代码（如 PptxTools 的模型透传）。
 */
public record ToolContext(
        Long projectId,
        String conversationId,
        Long userId,
        String modelId,
        java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools
) {

    /**
     * 本轮这个会话<b>本来</b>能用的全部工具（dev-board#810）：上游三层闸
     *（会话能力 / 活跃文档类型 / 运行期可用性）过完、渐进披露收窄<b>之前</b>的候选集。
     *
     * <p>只有 {@code list_tools} 读它——目录要列的正是「你没看见但确实有」的那些工具，
     * 而这份名单只有编排器算得出来（活跃文档类型是轮次级状态，登记簿里查不到）。
     * 不走 ThreadLocal 是因为工具分发已经有一个 {@link ToolContextHolder} 了，
     * 再加一个同生命周期的 ThreadLocal 只会多一处忘了清理的地方。
     *
     * <p>四参构造留给其余十几处调用方（子 Agent、插件控制器、各测试）：
     * 它们都不调 {@code list_tools}，给个空列表即可。
     */
    public ToolContext(Long projectId, String conversationId, Long userId, String modelId) {
        this(projectId, conversationId, userId, modelId, java.util.List.of());
    }

    public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools() {
        return sessionTools == null ? java.util.List.of() : sessionTools;
    }
}
