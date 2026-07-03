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
        String modelId
) {
}
