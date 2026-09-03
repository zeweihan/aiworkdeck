package com.checkba.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;

/**
 * 在 langchain4j 0.36 的 {@link StreamingResponseHandler} 之上补两条通道，给思考型模型用。
 *
 * <p>背景（dev-board#364）：OpenRouter 对 Kimi K3 这类思考型模型从第 4 秒起就在流式返回
 * {@code delta.reasoning}，同时 {@code delta.content} 恒为空串，直到思考结束才开始吐正文。
 * openai4j 0.23 的 {@code Delta} 只有 role/content/toolCalls/functionCall 四个字段，
 * reasoning 在反序列化那一刻就被丢掉；langchain4j 又只对非 null 的 content 调 {@code onNext}，
 * 于是几百秒的思考期间编排器收到的全是 {@code onNext("")}——看门狗被空 token 喂活、
 * 前端一个字节都收不到，用户分不清是死机还是在想。
 *
 * <p>两个方法都是 default 空实现：回放评测与各测试里的脚本模型仍按老接口写，不受影响。
 */
public interface ReasoningStreamingHandler extends StreamingResponseHandler<AiMessage> {

    /** 思考增量（OpenRouter 的 {@code delta.reasoning} / 各家原生的 {@code reasoning_content}）。 */
    default void onReasoning(String reasoningDelta) {
    }

    /**
     * 传输层有字节但不是内容：OpenRouter 在模型生成期间每隔几秒发一行
     * {@code : OPENROUTER PROCESSING} 注释保活。它证明「连接活着、上游还在跑」，
     * 看门狗据此刷新活动时间，不再把静默思考的模型当成死流。
     */
    default void onKeepAlive() {
    }

    /**
     * 本轮的提示缓存用量（只在上游真的回了缓存字段时调一次）。
     *
     * <p>为什么不走 {@code Response.tokenUsage()}：langchain4j 0.36 的 {@code TokenUsage}
     * 只有 input/output/total 三个数，openai4j 0.23 的 {@code Usage} 也只多一个
     * {@code completion_tokens_details}——{@code prompt_tokens_details.cached_tokens}
     * 在反序列化那一刻就被丢掉了，只能从原始 JSON 里读。
     *
     * <p>为什么挂在 handler 上而不在通道里直接打日志：模型实例按
     * (key, baseUrl, modelId) 缓存、跨会话共享，拿不到 conversationId；
     * 而 {@code AgentStreamHandler} 每轮新建，会话 id 就在它手里。
     *
     * @param promptTokens     本轮输入 token 总数
     * @param cachedTokens     其中从缓存读到的（按缓存读价计，约为输入价的 1/10）
     * @param cacheWriteTokens 本轮写入缓存的（5 分钟 TTL 按输入价 1.25x 计）
     */
    default void onCacheUsage(int promptTokens, int cachedTokens, int cacheWriteTokens) {
    }
}
