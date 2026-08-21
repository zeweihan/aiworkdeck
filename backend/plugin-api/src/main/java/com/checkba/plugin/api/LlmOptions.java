package com.checkba.plugin.api;

/** LLM 调用选项；modelId 为 null 时宿主用辅助模型（便宜档）。 */
public record LlmOptions(String modelId, double temperature, int maxTokens) {
    public static LlmOptions cheap() { return new LlmOptions(null, 0.0, 2048); }
}
