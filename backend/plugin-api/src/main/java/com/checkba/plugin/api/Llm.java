package com.checkba.plugin.api;

/** LLM 补全：走平台通道、扣用户 Credits、记 pluginId。 */
public interface Llm {
    String complete(String systemPrompt, String userPrompt, LlmOptions o);
}
