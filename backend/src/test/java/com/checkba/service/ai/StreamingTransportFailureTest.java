package com.checkba.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式通道的传输层失败必须回调 onError —— 编排器的整套可靠性层都挂在这个前提上。
 *
 * <p>病灶（2026-08-15 实测，AGENT 模式发一条要调工具的指令后零字节停滞 180s）：
 * openai4j 0.23 的 {@code StreamingRequestExecutor$2.onFailure} 在 {@code logResponses=true}
 * 时先调 {@code ResponseLoggingInterceptor.log(response)} 再走 errorHandler，
 * 而 okhttp-sse 的 {@code RealEventSource.onFailure(call, e)} 在「没拿到响应」这条路径上
 * 传的 response 恒为 null → {@code response.code()} 抛 NPE，且 onFailure 只 catch IOException，
 * 异常掀掉 OkHttp Dispatcher 线程、errorHandler 那一行永远走不到。
 * 结果本轮既不 onComplete 也不 onError，只能等 {@link AgentStreamHandler} 的看门狗兜底。
 * 后端日志里唯一的痕迹是：
 * <pre>
 * Exception in thread "OkHttp Dispatcher" java.lang.NullPointerException:
 *     Cannot invoke "okhttp3.Response.code()" because "response" is null
 *   at dev.ai4j.openai4j.ResponseLoggingInterceptor.logDebug(ResponseLoggingInterceptor.java:89)
 *   at dev.ai4j.openai4j.StreamingRequestExecutor$2.onFailure(StreamingRequestExecutor.java:211)
 *   at okhttp3.internal.sse.RealEventSource.onFailure(RealEventSource.kt:91)
 * </pre>
 *
 * <p>本用例走 {@link ChatModelFactory#streamingBuilder} —— 必须是工厂那份真实口径，
 * 用例里自己拼一个 builder 就永远是绿的，起不到守护作用。
 */
class StreamingTransportFailureTest {

    /** 端口上没有监听者 → 连接被拒 → IOException 先于任何响应到达，正是 response==null 那条路径。 */
    private static int portWithNothingListening() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    @DisplayName("连不上时必须回调 onError，而不是把异常吞在 OkHttp Dispatcher 上让本轮静默挂死")
    void transportFailureReachesOnError() throws Exception {
        StreamingChatLanguageModel model = ChatModelFactory.streamingBuilder(
                "test-key",
                "http://127.0.0.1:" + portWithNothingListening() + "/api/v1",
                "deepseek/deepseek-v4-flash",
                Duration.ofSeconds(5)).build();

        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<Throwable> captured = new AtomicReference<>();
        model.generate(List.of(UserMessage.from("hi")), new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                settled.countDown();
            }

            @Override
            public void onError(Throwable error) {
                captured.set(error);
                settled.countDown();
            }
        });

        assertTrue(settled.await(30, TimeUnit.SECONDS),
                "传输层失败必须走到终态回调；一直等下去说明异常被 openai4j 的 onFailure 吞了，"
                        + "线上表现就是 AGENT 模式点了发送后干等看门狗");
        assertNotNull(captured.get(), "必须是 onError 而不是 onComplete——静默按成功收尾会让编排器当作空回复处理");
    }
}
