package com.checkba.service.ai;

/**
 * 允许的模型白名单。
 * 防止前端传递恶意模型 ID 或非预期的高价模型。
 */
public enum AllowedModels {

    // 模型清单与 OpenRouter 实际在线模型对齐（2026-07 真机校验），
    // 下线模型请求会返回 404 "No endpoints found"，必须及时清理。
    // 注意：Google/Anthropic/OpenAI 系在国内网络环境会被 OpenRouter 返回
    // 403 "This model is not available in your region"，默认模型必须选区域无关的。

    // 区域无关（国内外均实测可用）
    DEEPSEEK_V4_FLASH("deepseek/deepseek-v4-flash", 0.09, 0.18),
    DEEPSEEK_V4_PRO("deepseek/deepseek-v4-pro", 0.43, 0.87),
    DEEPSEEK_V3_2("deepseek/deepseek-v3.2", 0.21, 0.32),
    QWEN_3_235B("qwen/qwen3-235b-a22b-2507", 0.09, 0.55),
    QWEN_2_5_72B("qwen/qwen-2.5-72b-instruct", 0.36, 0.4),
    GLM_5("z-ai/glm-5", 0.6, 1.92),
    KIMI_K2_6("moonshotai/kimi-k2.6", 0.66, 3.41),
    MINIMAX_M3("minimax/minimax-m3", 0.3, 1.2),
    LLAMA_3_3_70B("meta-llama/llama-3.3-70b-instruct", 0.1, 0.32),

    // Google (via OpenRouter，部分地区不可用)
    GEMINI_2_5_PRO("google/gemini-2.5-pro", 1.25, 10.0),
    GEMINI_2_5_FLASH("google/gemini-2.5-flash", 0.3, 2.5),
    GEMINI_2_5_FLASH_LITE("google/gemini-2.5-flash-lite", 0.1, 0.4),
    GEMINI_3_FLASH_PREVIEW("google/gemini-3-flash-preview", 0.5, 3.0),
    GEMINI_3_1_PRO_PREVIEW("google/gemini-3.1-pro-preview", 2.0, 12.0),

    // Anthropic (部分地区不可用)
    CLAUDE_SONNET_5("anthropic/claude-sonnet-5", 2.0, 10.0),
    CLAUDE_HAIKU_4_5("anthropic/claude-haiku-4.5", 1.0, 5.0),
    CLAUDE_3_HAIKU("anthropic/claude-3-haiku", 0.25, 1.25),

    // OpenAI (部分地区不可用)
    GPT_5_2("openai/gpt-5.2", 1.75, 14.0),
    GPT_4O("openai/gpt-4o", 2.5, 10.0),
    GPT_4O_MINI("openai/gpt-4o-mini", 0.15, 0.6);



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
