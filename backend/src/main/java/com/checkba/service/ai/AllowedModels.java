package com.checkba.service.ai;

/**
 * 允许的模型白名单。
 * 防止前端传递恶意模型 ID 或非预期的高价模型。
 */
public enum AllowedModels {

    // 模型清单与 OpenRouter 实际在线模型对齐（2026-07 校验），
    // 下线模型请求会返回 404 "No endpoints found"，必须及时清理。
    // Google (via OpenRouter)
    GEMINI_2_5_PRO("google/gemini-2.5-pro", 1.25, 10.0),
    GEMINI_2_5_FLASH("google/gemini-2.5-flash", 0.3, 2.5),
    GEMINI_2_5_FLASH_LITE("google/gemini-2.5-flash-lite", 0.1, 0.4),
    GEMINI_3_FLASH_PREVIEW("google/gemini-3-flash-preview", 0.5, 3.0),
    GEMINI_3_1_PRO_PREVIEW("google/gemini-3.1-pro-preview", 2.0, 12.0),

    // Anthropic
    CLAUDE_SONNET_5("anthropic/claude-sonnet-5", 2.0, 10.0),
    CLAUDE_HAIKU_4_5("anthropic/claude-haiku-4.5", 1.0, 5.0),
    CLAUDE_3_HAIKU("anthropic/claude-3-haiku", 0.25, 1.25),

    // OpenAI
    GPT_5_2("openai/gpt-5.2", 1.75, 14.0),
    GPT_4O("openai/gpt-4o", 2.5, 10.0),
    GPT_4O_MINI("openai/gpt-4o-mini", 0.15, 0.6),

    // Open Source
    LLAMA_3_3_70B("meta-llama/llama-3.3-70b-instruct", 0.1, 0.32),
    QWEN_2_5_72B("qwen/qwen-2.5-72b-instruct", 0.36, 0.4),
    DEEPSEEK_V3_2("deepseek/deepseek-v3.2", 0.21, 0.32);



    private final String modelId;
    /**
     * 预估输入价格 ($/1M tokens)
     */
    private final double inputPricePerM;
    /**
     * 预估输出价格 ($/1M tokens)
     */
    private final double outputPricePerM;

    AllowedModels(String modelId, double inputPricePerM, double outputPricePerM) {
        this.modelId = modelId;
        this.inputPricePerM = inputPricePerM;
        this.outputPricePerM = outputPricePerM;
    }

    public String getModelId() {
        return modelId;
    }

    public double getInputPricePerM() {
        return inputPricePerM;
    }

    public double getOutputPricePerM() {
        return outputPricePerM;
    }

    public static boolean isAllowed(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return false;
        for (AllowedModels m : values()) {
            if (m.modelId.equalsIgnoreCase(modelId)) return true;
        }
        return false;
    }
    
    public static AllowedModels fromId(String modelId) {
        for (AllowedModels m : values()) {
            if (m.modelId.equalsIgnoreCase(modelId)) return m;
        }
        return null;
    }
}
