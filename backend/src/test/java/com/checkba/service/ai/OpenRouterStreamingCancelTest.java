// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import okhttp3.Call;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途 HTTP 请求必须真的能掐断（计划 K4 ①；审计 C-03）。
 *
 * <p>病灶：{@code generate} 把 {@code send(...)} 返回的 okhttp {@link Call} 直接丢弃，
 * 于是用户点停止之后上游照样把这一轮生成完——输出 token 全额计费（平台通道花平台的钱，
 * BYOK 花用户的钱），而且被放弃的那次 AsyncCall 一直占着 Dispatcher 的请求槽位。
 * 同一个类里 {@code generateCancellable} 早就返回了 Call，只有写作建议那条路在用。
 */
class OpenRouterStreamingCancelTest {

    private HttpServer server;
    /** 服务端是否已经开始往外写（确保取消发生在「流已建立」之后，而不是连接还没起来）。 */
    private final CountDownLatch serverStreaming = new CountDownLatch(1);
    private final AtomicBoolean serverFinishedNormally = new AtomicBoolean();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(("data: {\"id\":\"gen-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"开头\"},\"finish_reason\":null}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                serverStreaming.countDown();
                // 上游还在慢慢生成：每 50ms 一个保活注释，最多 30 秒。
                // 取消生效时这里会因为连接被关而抛 IOException——那正是「真的掐断了」。
                for (int i = 0; i < 600; i++) {
                    os.write(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(50);
                }
                serverFinishedNormally.set(true);
            } catch (Exception ignored) {
                // 连接被取消端关掉，属预期
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenRouterStreamingChatModel model() {
        return new OpenRouterStreamingChatModel("test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "deepseek/deepseek-v4-flash", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("generateTracked 交回在途请求句柄；cancel 之后这一轮当场结束，不再等上游写完")
    void trackedGenerationCanBeCancelledMidStream() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        Call call = model().generateTracked(List.of(UserMessage.from("写一份很长的协议")), null,
                new StreamingResponseHandler<AiMessage>() {
                    @Override public void onNext(String token) { }
                    @Override public void onComplete(Response<AiMessage> response) {
                        completed.set(true);
                        terminal.countDown();
                    }
                    @Override public void onError(Throwable t) {
                        error.set(t);
                        terminal.countDown();
                    }
                });

        assertNotNull(call, "聊天主链路拿不到在途请求句柄就没法掐断它");
        assertTrue(serverStreaming.await(10, TimeUnit.SECONDS), "服务端没能开始流式输出");

        long t0 = System.nanoTime();
        call.cancel();
        assertTrue(terminal.await(5, TimeUnit.SECONDS),
                "取消之后 5 秒仍没有终态回调——请求根本没被掐断");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(call.isCanceled(), "Call 应当处于已取消状态");
        assertFalse(completed.get(), "被取消的请求不该走 onComplete（那是「上游写完了」的语义）");
        assertNotNull(error.get(), "okhttp 取消会以 IOException 形式回到 onError");
        assertTrue(elapsedMs < 2000, "取消后 " + elapsedMs + "ms 才结束，上游还在替我们烧 token");
        assertFalse(serverFinishedNormally.get(), "服务端竟然把整条流写完了，说明连接没断");
    }

    @Test
    @DisplayName("startGeneration：OpenRouter 通道交回可取消句柄，其它通道照常 generate 并交回 null")
    void startGenerationOnlyTracksTheCancellableTransport() throws Exception {
        AtomicBoolean plainModelCalled = new AtomicBoolean();
        StreamingChatLanguageModel plain = new StreamingChatLanguageModel() {
            @Override
            public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                generate(messages, List.of(), handler);
            }
            @Override
            public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                 StreamingResponseHandler<AiMessage> handler) {
                plainModelCalled.set(true);
                handler.onComplete(Response.from(AiMessage.from("ok")));
            }
        };

        StreamingResponseHandler<AiMessage> noop = new StreamingResponseHandler<>() {
            @Override public void onNext(String token) { }
            @Override public void onComplete(Response<AiMessage> response) { }
            @Override public void onError(Throwable error) { }
        };

        assertNull(AgentOrchestrator.startGeneration(plain, List.of(UserMessage.from("hi")), List.of(), noop),
                "本地 Ollama / 脚本模型没有可取消的在途请求，必须交回 null 而不是假句柄");
        assertTrue(plainModelCalled.get(), "不可取消的通道仍然要照常发起生成");

        Runnable canceller = AgentOrchestrator.startGeneration(model(),
                List.of(UserMessage.from("hi")), null, noop);
        assertNotNull(canceller, "OpenRouter 通道必须交回可取消句柄");
        canceller.run();
    }
}
