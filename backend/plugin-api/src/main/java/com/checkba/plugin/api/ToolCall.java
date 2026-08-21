package com.checkba.plugin.api;

/** 一次工具调用的上下文快照：项目、会话、用户、模型。 */
public record ToolCall(Long projectId, String conversationId, Long userId, String modelId) {}
