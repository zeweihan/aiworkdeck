package com.checkba.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 大模型相关配置（供应商可切换）。
 *
 * 配置前缀：ai.model
 */
@Component
@ConfigurationProperties(prefix = "ai.model")
public class AiModelProperties {

    /**
     * 模型提供商（三档，2026-08 收敛）：
     * - AWD_CLOUD：平台通道「AI WorkDeck 云端」——密钥由官网按账户 provision，
     *   仍走 OpenRouter，但用户不必自备 key（Spec §3）。未连接账户时不可选。
     * - OPENROUTER: OpenRouter（OpenAI 兼容），用户自备 Key
     * - OLLAMA：本地 Ollama 服务，离线/实验档，只支持 ASK 模式
     *
     * <p>GEMINI 档已下线：手写的 GeminiChatLanguageModel 不支持 tools 也没有流式实现，
     * 在 AGENT/PLAN 模式下等于死路；Gemini 系列模型改由 OpenRouter 统一接入
     * （见 AllowedModels 的 google/gemini-3.6-flash）。存量 DB 里的 GEMINI 值
     * 由 ChatModelFactory 的启动期迁移改写成 OLLAMA。
     */
    public enum Provider {
        OLLAMA,
        OPENROUTER,
        AWD_CLOUD
    }

    /**
     * 当前使用的模型提供商，默认继续使用本地 Ollama。
     */
    private Provider provider = Provider.OLLAMA;

    /**
     * OpenRouter 配置 (兼容 OpenAI 接口)。
     */
    private OpenRouter openRouter = new OpenRouter();

    /**
     * 本地 Ollama 配置（用于兼容当前的本地大模型设置）。
     */
    private Ollama ollama = new Ollama();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public OpenRouter getOpenRouter() {
        return openRouter;
    }

    public void setOpenRouter(OpenRouter openRouter) {
        this.openRouter = openRouter;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public void setOllama(Ollama ollama) {
        this.ollama = ollama;
    }

    public static class OpenRouter {
        /**
         * OpenRouter API Key.
         */
        private String apiKey;
        /**
         * OpenRouter Base URL.
         */
        private String baseUrl = "https://openrouter.ai/api/v1";
        /**
         * Default model to use if not specified.
         */
        private String defaultModel = "deepseek/deepseek-v4-flash";
        /**
         * Timeout.
         */
        private Duration timeout = Duration.ofSeconds(120);

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Ollama {

        /**
         * 本地 Ollama 服务地址。
         */
        private String baseUrl = "http://localhost:11434";

        /**
         * 用于对话的模型名称。
         */
        private String modelName = "qwen3-vl:8b";

        /**
         * 采样温度。
         */
        private Double temperature = 0.7;

        /**
         * 请求超时时间。
         */
        private Duration timeout = Duration.ofSeconds(300);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }
}


