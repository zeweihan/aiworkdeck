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
     * 模型提供商：
     * - OLLAMA：本地 Ollama 服务
     * - GEMINI：Google Gemini 云端模型
     * - OPENROUTER: OpenRouter (OpenAI 兼容)
     */
    public enum Provider {
        OLLAMA,
        GEMINI,
        OPENROUTER
    }

    /**
     * 当前使用的模型提供商，默认继续使用本地 Ollama。
     */
    private Provider provider = Provider.OLLAMA;

    /**
     * Google Gemini 配置。
     */
    private Gemini gemini = new Gemini();

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

    public Gemini getGemini() {
        return gemini;
    }

    public void setGemini(Gemini gemini) {
        this.gemini = gemini;
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
        private String defaultModel = "google/gemini-2.5-flash-lite";
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

    public static class Gemini {

        /**
         * Gemini API 密钥（建议通过环境变量 GEMINI_API_KEY 注入）。
         */
        private String apiKey;

        /**
         * 使用的 Gemini 模型名称，例如：gemini-2.5-pro、gemini-2.5-flash 等。
         * 参考官方文档当前推荐的通用文本模型：https://ai.google.dev/gemini-api/docs/pricing?hl=zh-cn
         */
        private String modelName = "gemini-2.5-pro";

        /**
         * Gemini API 基础地址。
         */
        private String apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";

        /**
         * 请求超时时间。
         */
        private Duration timeout = Duration.ofSeconds(60);

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
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


