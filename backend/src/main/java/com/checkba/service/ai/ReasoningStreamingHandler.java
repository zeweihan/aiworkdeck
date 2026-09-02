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
}
