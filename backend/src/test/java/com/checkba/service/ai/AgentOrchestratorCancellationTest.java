// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillProperties;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「停止」这一下到底停没停（计划 K4；审计 C-03 / D-01 / D-10）。
 *
 * <p>改造前的三处真实缺陷，每条用例各守一处：
 * <ol>
 *   <li><b>取消只置一个布尔，流照吐</b>——{@code AgentStreamHandler.onNext} 全程不查取消标志，
 *       用户点停止之后上游继续把这一轮写完，输出 token 全额计费，前端只是「看不见」而已
 *       （abort 里先拆了本地 SSE）。</li>
 *   <li><b>取消轮次在历史里整条消失</b>——{@code handleCancellation} 只落模型正文，
 *       而 AGENT 模式下模型第一件事就是发工具调用、一个正文 token 都没有，于是那一轮
 *       一个字都不写，文件却其实已经被改过了。律师需要能回看「AI 当时动了哪几个文件」。</li>
 *   <li><b>没有活跃轮次也回 ok</b>——前端据此写「已发送停止指令」，掩盖真实失效。</li>
 * </ol>
 *
 * <p>交错点一律用 CountDownLatch 钉死，不靠 sleep 赌时序。
 */
class AgentOrchestratorCancellationTest {

    private static final String MODEL = "qwen/qwen3.7-flash";
    private static final String CONV = "conv-cancel";

    @TempDir
    Path tempDir;

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private AgentOrchestrator orchestrator;

    private final List<String[]> sseEvents = new CopyOnWriteArrayList<>();
    private final Map<Long, String> rows = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    /** 门控脚本模型：过闸之后继续吐 token，用来验证「取消之后一个字都不该再转发」。 */
    private static final class GatedModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        private final CountDownLatch reachedGate;
        private final CountDownLatch mayPassGate;
        private final List<String> preGateChunks;
        private final List<String> postGateChunks;
        int calls;

        GatedModel(List<AiMessage> script, CountDownLatch reachedGate, CountDownLatch mayPassGate,
                   List<String> preGateChunks, List<String> postGateChunks) {
            this.script = script;
            this.reachedGate = reachedGate;
            this.mayPassGate = mayPassGate;
            this.preGateChunks = preGateChunks;
            this.postGateChunks = postGateChunks;
        }

        static GatedModel ungated(AiMessage... script) {
            return new GatedModel(List.of(script), null, null, List.of(), List.of());
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            generate(messages, List.of(), handler);
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            int idx = calls++;
            AiMessage msg = script.get(Math.min(idx, script.size() - 1));
            if (idx == 0 && reachedGate != null) {
                for (String chunk : preGateChunks) handler.onNext(chunk);
                reachedGate.countDown();
                awaitQuietly(mayPassGate);
                for (String chunk : postGateChunks) handler.onNext(chunk);
            } else if (msg.text() != null && !msg.text().isEmpty()) {
                handler.onNext(msg.text());
            }
            handler.onComplete(Response.from(msg));
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        if (latch == null) return;
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "等待交错点超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        chatModelFactory = mock(ChatModelFactory.class);
        sse = mock(SseEmitterService.class);
        sseEvents.clear();
        rows.clear();
        doAnswer(inv -> {
            // 注意 String.valueOf(inv.getArgument(2)) 会被重载决议挑成 String.valueOf(char[])
            // 并在运行期 ClassCastException——必须先落成 Object
            Object payload = inv.getArgument(2);
            sseEvents.add(new String[]{inv.getArgument(1, String.class), String.valueOf(payload)});
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        when(messageService.countByConversationId(any()))
                .thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Long existing = inv.getArgument(3, Long.class);
            Long id = existing != null ? existing : idSeq.incrementAndGet();
            rows.put(id, inv.getArgument(4, String.class));
            return id;
        });

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("user"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any()))
                .thenReturn(List.of(ToolSpecification.builder().name("read_document").description("d").build()));
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("ok", null, true));

        SkillProperties skillProps = new SkillProperties();
        skillProps.setDir(tempDir.toString());
        skillProps.setBaseTools(List.of("read_document"));
        SkillRegistry skillRegistry = new SkillRegistry(skillProps, null, new PluginService(), null);
        skillRegistry.init();
        SkillRouter skillRouter = new SkillRouter(skillRegistry, skillProps,
                new com.checkba.service.telemetry.TelemetryService(
                        mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(tempDir.toString()),
                        "test"),
                null);

        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class),
                new AgentRunStateService(mock(com.checkba.repository.AgentRunRecordRepository.class),
                        mock(com.checkba.service.telemetry.TelemetryTurnTracker.class)),
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new OfficePassStateStore());
    }

    private static AiAgentController.AgentChatRequest request(String message) {
        AiAgentController.AgentChatRequest r = new AiAgentController.AgentChatRequest();
        r.setProjectId(1L);
        r.setConversationId(CONV);
        r.setMessage(message);
        r.setModel(MODEL);
        return r;
    }

    private Thread runAsync(String message, StreamingChatLanguageModel model) {
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        Thread t = new Thread(() -> orchestrator.handleUserMessage(request(message), 7L), "cancel-turn");
        t.start();
        return t;
    }

    private static void join(Thread t) throws InterruptedException {
        t.join(20_000);
        assertFalse(t.isAlive(), "轮次线程未在 20s 内收尾，多半是死锁");
    }

    private String textDeltas() {
        StringBuilder sb = new StringBuilder();
        for (String[] e : sseEvents) if ("text_delta".equals(e[0])) sb.append(e[1]);
        return sb.toString();
    }

    private long countOf(String eventName) {
        return sseEvents.stream().filter(e -> eventName.equals(e[0])).count();
    }

    // =====================================================================================
    // ① 取消之后不再转发 token
    // =====================================================================================

    @Test
    @DisplayName("取消之后 stream handler 不再转发任何 token，本轮终态是 cancelled 而不是 error")
    void cancelledRunForwardsNoFurtherTokens() throws Exception {
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch mayContinue = new CountDownLatch(1);
        List<String> after = new ArrayList<>();
        for (int i = 0; i < 40; i++) after.add("取消之后的第" + i + "段。");

        GatedModel model = new GatedModel(
                List.of(AiMessage.from("取消之前的开头。")),
                streaming, mayContinue,
                List.of("取消之前的开头。"), after);

        Thread t = runAsync("写一份很长的协议", model);
        assertTrue(streaming.await(10, TimeUnit.SECONDS), "模型没能开始流式输出");

        assertTrue(orchestrator.setCancelled(CONV), "有活跃轮次时 setCancelled 必须报告打中了");
        mayContinue.countDown();
        join(t);

        String streamed = textDeltas();
        assertTrue(streamed.contains("取消之前的开头"), "取消之前已经流出的内容应当照常可见：" + streamed);
        assertFalse(streamed.contains("取消之后的第"),
                "取消之后仍在往 SSE 上转发 token——上游那一轮还在按全价计费，用户只是看不见：" + streamed);
        assertEquals(1, countOf("cancelled"), "取消必须恰好发一次 cancelled 事件：" + sseEvents);
        assertEquals(0, countOf("error"), "取消不是错误，不许发 error 事件：" + sseEvents);
    }

    // =====================================================================================
    // ② 取消轮次的执行日志必须落库（正文为空也落）
    // =====================================================================================

    /**
     * 刻意用<b>两个</b>工具、在第一个执行中途取消。
     *
     * <p>整批工具跑完之后有一次「增量保存」（native 分支循环之后），所以「一个工具、跑完再取消」
     * 那条路的执行日志其实已经被它捎上了——真正会丢的是这一档：取消落在批次<b>中间</b>，
     * 逐个工具前的检查点直接 {@code return}，那次增量保存整段跳过。这正是「AI 已经动过文件、
     * 用户点了停止、刷新后历史里什么都没有」的形态。
     */
    @Test
    @DisplayName("工具批次中途取消：已执行工具的执行日志随停机落库，哪怕模型一个正文 token 都没吐")
    void cancellingBetweenToolsPersistsExecutionLog() throws Exception {
        CountDownLatch firstToolStarted = new CountDownLatch(1);
        CountDownLatch mayFinishFirstTool = new CountDownLatch(1);
        when(toolRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            if (firstToolStarted.getCount() > 0) {
                firstToolStarted.countDown();
                awaitQuietly(mayFinishFirstTool);
                return new ToolRegistry.ToolResult("文件已读取", null, true);
            }
            return new ToolRegistry.ToolResult("第二个工具不该被执行", null, true);
        });

        // 第一轮只发工具调用，一个正文 token 都没有（AGENT 模式下最常见的形态）
        GatedModel model = GatedModel.ungated(
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("1").name("read_document")
                                .arguments("{\"fileId\":1}").build(),
                        ToolExecutionRequest.builder().id("2").name("read_document")
                                .arguments("{\"fileId\":2}").build())),
                AiMessage.from("<final>不该走到这里</final>"));

        Thread t = runAsync("读一下这两份合同", model);
        assertTrue(firstToolStarted.await(10, TimeUnit.SECONDS), "工具没能开始执行");
        assertTrue(orchestrator.setCancelled(CONV));
        mayFinishFirstTool.countDown();
        join(t);

        assertFalse(rows.isEmpty(),
                "模型没吐正文就被停止 → 整轮在历史里一个字都没有，而文件其实已经被读/改过了");
        String saved = String.join("\n", rows.values());
        assertTrue(saved.contains("<tool_code>read_document"),
                "取消轮次必须落执行日志，否则刷新后所有工具过程卡消失：" + saved);
        assertTrue(saved.contains("[已中断]"), "落库内容要明确标出这一轮是被中断的：" + saved);
    }

    // =====================================================================================
    // ③ 没有活跃轮次时 setCancelled 报告「没打中」
    // =====================================================================================

    @Test
    @DisplayName("没有活跃轮次时 setCancelled 返回 false（前端据此说「该轮次已经结束」而不是「已发送停止指令」）")
    void cancelWithoutActiveRunReportsMiss() throws Exception {
        assertFalse(orchestrator.setCancelled(CONV), "从没跑过的会话不该报告打中");

        Thread t = runAsync("你好", GatedModel.ungated(AiMessage.from("<final>好的</final>")));
        join(t);

        assertFalse(orchestrator.setCancelled(CONV), "轮次已经自己收尾了，停止打不中任何东西");
    }
}
