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
        java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools,
        java.util.Set<String> offeredTools,
        String runId,
        java.util.Set<String> disclosedCategories,
        java.util.function.BooleanSupplier cancellationCheck,
        Long defaultOutputFolderId
) {

    public ToolContext(Long projectId, String conversationId, Long userId, String modelId,
                       java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools,
                       java.util.Set<String> offeredTools, String runId,
                       java.util.Set<String> disclosedCategories,
                       java.util.function.BooleanSupplier cancellationCheck) {
        this(projectId, conversationId, userId, modelId, sessionTools, offeredTools, runId,
                disclosedCategories, cancellationCheck, null);
    }

    /** Cancellation belongs to the originating run, not whichever run is now active in its conversation. */
    public boolean isCancelled() {
        return cancellationCheck != null && cancellationCheck.getAsBoolean();
    }

    public ToolContext(Long projectId, String conversationId, Long userId, String modelId,
                       java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools,
                       java.util.Set<String> offeredTools, String runId,
                       java.util.Set<String> disclosedCategories) {
        this(projectId, conversationId, userId, modelId, sessionTools, offeredTools, runId,
                disclosedCategories, null);
    }

    /**
     * 本轮这个会话<b>本来</b>能用的全部工具（dev-board#810）：上游三层闸
     *（会话能力 / 活跃文档类型 / 运行期可用性）过完、渐进披露收窄<b>之前</b>的候选集。
     *
     * <p>只有 {@code list_tools} 读它——目录要列的正是「你没看见但确实有」的那些工具，
     * 而这份名单只有编排器算得出来（活跃文档类型是轮次级状态，登记簿里查不到）。
     * 不走 ThreadLocal 是因为工具分发已经有一个 {@link ToolContextHolder} 了，
     * 再加一个同生命周期的 ThreadLocal 只会多一处忘了清理的地方。
     *
     * <p>四参构造留给未提供工具目录的旧调用方（插件控制器、各测试）：
     * 它们都不调 {@code list_tools}，给个空列表即可。
     */
    public ToolContext(Long projectId, String conversationId, Long userId, String modelId) {
        this(projectId, conversationId, userId, modelId, java.util.List.of(), null, null, null);
    }

    /** 只带候选集、不知道本轮真正下发了哪些的旧入口（offeredTools 为 null = 不知道）。 */
    public ToolContext(Long projectId, String conversationId, Long userId, String modelId,
                       java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools) {
        this(projectId, conversationId, userId, modelId, sessionTools, null, null, null);
    }

    /** 带本轮下发集、不带轮次标识与回写通道的入口（dev-board#1064 的形状）。 */
    public ToolContext(Long projectId, String conversationId, Long userId, String modelId,
                       java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools,
                       java.util.Set<String> offeredTools) {
        this(projectId, conversationId, userId, modelId, sessionTools, offeredTools, null, null);
    }

    public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> sessionTools() {
        return sessionTools == null ? java.util.List.of() : sessionTools;
    }

    /**
     * 本轮<b>真正下发给模型</b>的工具名（dev-board#1064）。{@code list_tools} 的目录页列的是
     * 「候选集 − 本集合」：只开了活跃文档类目裁剪、没开渐进披露时，核心集以外的大半工具其实都在
     * 模型手上，按「非核心」列会把它们也当成没看见的列一遍。null = 调用方不知道（旧入口），
     * 此时 {@code list_tools} 退回「非核心即未下发」的旧口径。
     */
    public java.util.Set<String> offeredTools() {
        return offeredTools;
    }

    /**
     * 本轮的轮次标识（dev-board#1065）。{@code use_skill} 与 {@code list_tools} 据它判断
     * 「这个 skill 本轮是不是已经生效了」——skill 的生效登记按 runId 索引（dev-board#533），
     * conversationId 分不开同一会话的两个并发轮次。null = 不参与主轮技能状态（子 Agent、插件控制器、各测试）。
     */
    public String runId() {
        return runId;
    }

    /**
     * 工具回写给编排器的「这次真正展示了全签名的类目」（dev-board#1065）。
     *
     * <p>{@code list_tools(query=…)} / {@code list_tools(names=…)} 命中哪些工具是工具自己算出来的，
     * 编排器只看参数推不出来，而从渲染出来的文字里反解又脆（改一个标题格式就静默失效）。
     * 所以编排器在分发前放进一个可写的集合，工具往里加，分发后编排器读它记进展开集——
     * 结构化的回写通道，不解析输出文本。null = 调用方不收（旧入口），工具照常只返回文字。
     */
    public java.util.Set<String> disclosedCategories() {
        return disclosedCategories;
    }
}
