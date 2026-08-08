package com.checkba.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务统一配置：按 ai.model.provider 构建一个「静态配置」的兜底 ChatLanguageModel。
 *
 * <p>真正的对话链路一律走 {@link com.checkba.service.ai.ChatModelFactory}——只有它读
 * system_setting 的 ai.activeProvider、支持 per-user 平台密钥与运行时改配置后清缓存。
 */
@Configuration
@RequiredArgsConstructor
public class AiConfiguration {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiConfiguration.class);

    private final AiModelProperties aiModelProperties;

    /**
     * 统一的 ChatLanguageModel，根据 ai.model.provider 切换供应商。
     *
     * <p>GEMINI 档已下线（手写实现不支持 tools、也没有流式），Gemini 系列模型改由 OpenRouter 接入。
     *
     * <p>顺带修一个既有 bug：OPENROUTER / AWD_CLOUD 之前一起落进 {@code case OLLAMA: default:}，
     * 于是打包态（provider=open-router）启动时构造出来的其实是 OllamaChatModel——
     * langchain4j 是懒连接才没在启动期炸，一旦有人真拿这个 bean 发请求就会打到 localhost:11434。
     *
     * <p>但云端两档只在「yml 里真配了静态 key」时才构造 OpenAI 兼容客户端：openai4j 的
     * {@code openAiApiKey()} 对 null/空串直接抛 IllegalArgumentException，而桌面版的常态是
     * {@code api-key: ${OPENROUTER_API_KEY:}} 为空、密钥存在 DB（平台通道更是 per-user、
     * 启动期根本取不到）——无条件构造会把整个进程的启动打挂。缺静态 key 时退回 Ollama 占位实例
     * 并 warn，反正实际路由不经过这个 bean。
     */
    @Bean
    public ChatLanguageModel projectChatLanguageModel() {
        AiModelProperties.Provider provider = aiModelProperties.getProvider();
        if (provider == null) {
            provider = AiModelProperties.Provider.OLLAMA;
        }

        if (provider == AiModelProperties.Provider.OPENROUTER
                || provider == AiModelProperties.Provider.AWD_CLOUD) {
            AiModelProperties.OpenRouter orCfg = aiModelProperties.getOpenRouter();
            if (orCfg.getApiKey() != null && !orCfg.getApiKey().isBlank()) {
                log.info("Using OpenRouter-compatible chat model: baseUrl={} model={}",
                        orCfg.getBaseUrl(), orCfg.getDefaultModel());
                return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                        .apiKey(orCfg.getApiKey())
                        .baseUrl(orCfg.getBaseUrl())
                        .modelName(orCfg.getDefaultModel())
                        .timeout(orCfg.getTimeout())
                        .build();
            }
            log.warn("provider={} 但 yml 未提供静态 OpenRouter key，兜底 bean 退回 Ollama 占位实例；"
                    + "实际对话由 ChatModelFactory 按 system_setting 里的密钥路由", provider);
        }

        AiModelProperties.Ollama ollamaCfg = aiModelProperties.getOllama();
        log.info("Using Ollama chat model: baseUrl={} model={}",
                ollamaCfg.getBaseUrl(), ollamaCfg.getModelName());
        return dev.langchain4j.model.ollama.OllamaChatModel.builder()
                .baseUrl(ollamaCfg.getBaseUrl())
                .modelName(ollamaCfg.getModelName())
                .temperature(ollamaCfg.getTemperature())
                .timeout(ollamaCfg.getTimeout())
                .build();
    }
}
