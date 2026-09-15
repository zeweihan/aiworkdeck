// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.PluginService;
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
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一 conversationId 上两个<b>并发轮次</b>的隔离（dev-board#533，审计「留给维护者拍板」第 2、4 条）。
 *
 * <p>守三件事，每一件在改造前都是真实可复现的数据损坏：
 * <ol>
 *   <li><b>助手消息行互相覆盖</b>——{@code activeAssistantMessageId} 是 conversationId 级的单槽，
 *       后起的一轮把它重置，先起的那一轮收尾时拿到<b>别人的行 id</b> 去 update，
 *       于是两轮回复合并成一行、其中一轮的正文永久消失。</li>
 *   <li><b>「停止后立刻再发」擦掉取消标志</b>——{@code cancelledConversations} 也是 conversationId 级的，
 *       新一轮开头无条件 {@code remove(conversationId)}，把上一轮尚未生效的取消标志抹掉，
 *       用户点了停止、旧轮次却一路跑到底（还会继续烧 token、继续改文档）。</li>
 *   <li><b>SkillRouter 无轮次隔离</b>——{@code activateForTurn} 按 conversationId 覆盖写，
 *       后起一轮的 skill 会把先起那一轮<b>正在跑的循环</b>的工具白名单换掉：
 *       第一轮的 prompt 注入的是 A 技能，第二轮起可见工具却成了 B 技能的。</li>
 * </ol>
 *
 * <p><b>交错必须是确定性的</b>：全部用 CountDownLatch 卡在假模型/假工具里控制先后，
 * 不靠 sleep 赌时序（睡出来的绿是假绿，CI 上一慢就翻红）。
 */
class AgentOrchestratorConcurrentTurnsTest {

    private static final String MODEL = "qwen/qwen3.7-flash";
    private static final String CONV = "conv-concurrent";

    @TempDir
    Path tempDir;

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private AgentRunStateService runState;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private SkillRegistry skillRegistry;
    private SkillRouter skillRouter;
    private EditorBridgeService editorBridge;
    private AgentOrchestrator orchestrator;

    /** 实际打到 emitter 上的会话级事件：[事件名, 载荷字符串]。轮次隔离的判据全在这上面。 */
    private final List<String[]> sseEvents = new CopyOnWriteArrayList<>();

    /** 假的 ASSISTANT 消息表：id -> 正文。update 落到哪一行由被测代码传进来的 existingMessageId 决定。 */
    private final Map<Long, String> rows = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    /**
     * 可被 latch 卡住的脚本模型：第 n 轮吐 script[n]，吐完（onNext 之后、onComplete 之前）
     * 先 countDown(streaming) 再 await(release)——调用方据此把两轮的交错点钉死。
     */
    private static final class GatedModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        /** 卡在第几轮 generate（0 起）；负数表示不卡。 */
        private final int gateRound;
        private final CountDownLatch reachedGate;
        private final CountDownLatch mayPassGate;
        final List<List<String>> offeredToolsPerRound = new CopyOnWriteArrayList<>();
        int calls;
        /** 过闸之后才吐的正文增量（模拟「被新一轮取代之后旧轮次还在吐 token」）。 */
        private final List<String> postGateChunks;
        /** 过闸之后才吐的思考增量；null 表示不吐。 */
        private final String postGateReasoning;

        GatedModel(List<AiMessage> script, int gateRound,
                   CountDownLatch reachedGate, CountDownLatch mayPassGate) {
            this(script, gateRound, reachedGate, mayPassGate, List.of(), null);
        }

        GatedModel(List<AiMessage> script, int gateRound,
                   CountDownLatch reachedGate, CountDownLatch mayPassGate,
                   List<String> postGateChunks, String postGateReasoning) {
            this.script = script;
            this.gateRound = gateRound;
            this.reachedGate = reachedGate;
            this.mayPassGate = mayPassGate;
            this.postGateChunks = postGateChunks;
            this.postGateReasoning = postGateReasoning;
        }

        static GatedModel ungated(AiMessage... script) {
            return new GatedModel(List.of(script), -1, null, null);
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            generate(messages, List.of(), handler);
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            int idx = calls++;
            offeredToolsPerRound.add(tools.stream().map(ToolSpecification::name).toList());
            AiMessage msg = script.get(Math.min(idx, script.size() - 1));
            if (msg.text() != null && !msg.text().isEmpty()) {
                handler.onNext(msg.text());
            }
            if (idx == gateRound) {
                if (reachedGate != null) reachedGate.countDown();
                awaitQuietly(mayPassGate);
                for (String chunk : postGateChunks) {
                    handler.onNext(chunk);
                }
                if (postGateReasoning != null && handler instanceof ReasoningStreamingHandler reasoning) {
                    reasoning.onReasoning(postGateReasoning);
                }
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
        // SSE 事件全部记下来：前三条用例只要它不抛异常，第四条用例的判据全在这份流水上
        sse = mock(SseEmitterService.class);
        sseEvents.clear();
        doAnswer(inv -> {
            Object payload = inv.getArgument(2);
            sseEvents.add(new String[]{inv.getArgument(1, String.class), String.valueOf(payload)});
            return null;
        }).when(sse).send(any(), any(), any());
        editorBridge = mock(EditorBridgeService.class);

        messageService = mock(ProjectAiMessageService.class);
        // >1 条历史：跳过首轮标题生成那条跨线程分支，本用例只关心消息行归属
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Long existing = inv.getArgument(3, Long.class);
            String content = inv.getArgument(4, String.class);
            Long id = existing != null ? existing : idSeq.incrementAndGet();
            rows.put(id, content);
            return id;
        });

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("user"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any()))
                .thenReturn(specs("law_search", "mask_text", "read_document"));
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("ok", null, true));

        // 真实的 SkillRouter：轮次隔离要守的正是它的内部登记簿，mock 掉就等于没测
        writeSkill("skill-listing", List.of("上市"), List.of("law_search"));
        writeSkill("skill-mask", List.of("脱敏"), List.of("mask_text"));
        SkillProperties skillProps = new SkillProperties();
        skillProps.setDir(tempDir.toString());
        skillProps.setBaseTools(List.of("read_document"));
        skillRegistry = new SkillRegistry(skillProps, null, new PluginService(), null);
        skillRegistry.init();
        skillRouter = new SkillRouter(skillRegistry, skillProps,
                new com.checkba.service.telemetry.TelemetryService(
                        mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(tempDir.toString()),
                        "test"),
                null);

        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), editorBridge,
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new OfficePassStateStore());
    }

    private void writeSkill(String id, List<String> triggers, List<String> allowedTools) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        StringBuilder yml = new StringBuilder("id: " + id + "\nname: " + id + "\ntriggers:\n");
        triggers.forEach(t -> yml.append("  - ").append(t).append("\n"));
        yml.append("allowed_tools:\n");
        allowedTools.forEach(t -> yml.append("  - ").append(t).append("\n"));
        Files.writeString(dir.resolve("skill.yml"), yml.toString());
        Files.writeString(dir.resolve("prompt.md"), "prompt of " + id);
    }

    private static List<ToolSpecification> specs(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> ToolSpecification.builder().name(n).description(n).build())
                .toList();
    }

    private static AiAgentController.AgentChatRequest request(String message) {
        AiAgentController.AgentChatRequest r = new AiAgentController.AgentChatRequest();
        r.setProjectId(1L);
        r.setConversationId(CONV);
        r.setMessage(message);
        r.setModel(MODEL);
        return r;
    }

    /** 在独立线程上跑一轮，返回该线程（调用方负责 join）。 */
    private Thread runAsync(String message, GatedModel model) {
        Thread t = new Thread(() -> {
            when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
            orchestrator.handleUserMessage(request(message), 7L);
        }, "turn-" + message);
        t.start();
        return t;
    }

    private static void join(Thread t) throws InterruptedException {
        t.join(15_000);
        assertFalse(t.isAlive(), "轮次线程未在 15s 内收尾，多半是死锁");
    }

    private boolean anyRowContains(String needle) {
        return rows.values().stream().anyMatch(v -> v != null && v.contains(needle));
    }

    // =====================================================================================
    // ① 两轮并发：各自的助手消息都要完整落库，互不覆盖
    // =====================================================================================

    @Test
    @DisplayName("同一会话两轮并发：两轮各自的助手消息都完整落库，不互相覆盖")
    void concurrentTurnsPersistToSeparateMessageRows() throws Exception {
        CountDownLatch firstInSecondRound = new CountDownLatch(1);
        CountDownLatch firstMayFinish = new CountDownLatch(1);

        // 第一轮：先跑一次工具（于是有一次「增量保存」落下行 id），再在第二轮 generate 里卡住
        GatedModel first = new GatedModel(List.of(
                AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("1").name("law_search").arguments("{}").build())),
                AiMessage.from("<final>第一轮的完整回答</final>")),
                1, firstInSecondRound, firstMayFinish);
        // 第二轮不卡：起跑后一路跑完，落自己的行
        GatedModel second = GatedModel.ungated(AiMessage.from("<final>第二轮的完整回答</final>"));

        Thread t1 = runAsync("第一轮的问题", first);
        assertTrue(firstInSecondRound.await(10, TimeUnit.SECONDS), "第一轮没进到第二次 generate");

        // 第一轮还在跑，第二轮起跑并跑完（真实场景：双击发送 / 两个标签页 / 客户端重试）
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(second);
        orchestrator.handleUserMessage(request("第二轮的问题"), 7L);

        // 第二轮已经落库之后，第一轮才收尾——它必须回到自己那一行，不许另起一行、更不许改别人的
        firstMayFinish.countDown();
        join(t1);

        assertTrue(anyRowContains("第一轮的完整回答"),
                "第一轮的回复被覆盖掉了，历史里查无此轮：" + rows);
        assertTrue(anyRowContains("第二轮的完整回答"),
                "第二轮的回复被覆盖掉了，历史里查无此轮：" + rows);
        assertEquals(2, rows.size(),
                "两轮必须各占一行：多出来的行是第一轮被挤成了两条气泡（增量保存与最终保存落到了不同行），"
                        + "少了的行是某一轮被另一轮覆盖。实际内容：" + rows);
        // 第一轮的过程卡与结论必须在同一行：分家等于历史里出现一条没有结论的半截气泡
        String firstRow = rows.values().stream()
                .filter(v -> v != null && v.contains("第一轮的完整回答"))
                .findFirst().orElseThrow();
        assertTrue(firstRow.contains("law_search"),
                "第一轮的工具过程日志与最终回复分到了两行：" + rows);
    }

    // =====================================================================================
    // ② 停止后立刻再发：新一轮不受旧取消标志影响，旧一轮确实停下来
    // =====================================================================================

    @Test
    @DisplayName("停止后立刻再发：新一轮不被旧取消标志影响，旧一轮确实停止")
    void cancelThenImmediateResendStopsOldTurnOnly() throws Exception {
        CountDownLatch oldStreaming = new CountDownLatch(1);
        CountDownLatch oldMayFinish = new CountDownLatch(1);

        GatedModel oldTurn = new GatedModel(
                List.of(AiMessage.from("旧轮次已经流出的半截正文")), 0, oldStreaming, oldMayFinish);
        GatedModel newTurn = GatedModel.ungated(AiMessage.from("<final>新一轮的完整回答</final>"));

        Thread t1 = runAsync("旧的问题", oldTurn);
        assertTrue(oldStreaming.await(10, TimeUnit.SECONDS), "旧轮次没进入流式");

        // 用户点「停止」，紧接着（50ms 内，这里直接顺序执行）又发了一条新消息
        orchestrator.setCancelled(CONV);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(newTurn);
        orchestrator.handleUserMessage(request("停止后立刻再发的新问题"), 7L);

        oldMayFinish.countDown();
        join(t1);

        // 新一轮：完整跑完、正文落库，不能被上一轮的取消标志误伤
        assertTrue(anyRowContains("新一轮的完整回答"),
                "新一轮被旧的取消标志掐掉了：" + rows);
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get(CONV).status(),
                "会话终态应由新一轮决定，旧一轮的取消不该盖掉它");
        // 旧一轮：取消真的生效，半截正文按中断落库
        String interrupted = rows.values().stream()
                .filter(v -> v != null && v.contains("旧轮次已经流出的半截正文"))
                .findFirst().orElse(null);
        assertNotNull(interrupted, "旧一轮的半截正文丢了：" + rows);
        assertTrue(interrupted.contains("[已中断]"),
                "旧一轮没有停：新一轮起跑时把它的取消标志擦掉了（" + interrupted + "）");
    }

    // =====================================================================================
    // ③ SkillRouter：两轮并发下各自命中各自的 skill
    // =====================================================================================

    @Test
    @DisplayName("两轮并发各自命中各自的 skill：先起的那一轮，可见工具不被后起一轮换掉")
    void concurrentTurnsKeepTheirOwnSkill() throws Exception {
        CountDownLatch firstToolRunning = new CountDownLatch(1);
        CountDownLatch firstToolMayReturn = new CountDownLatch(1);

        // 第一轮：先调一次工具（于是会有第二轮 generate），工具执行期间卡住
        when(toolRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            firstToolRunning.countDown();
            awaitQuietly(firstToolMayReturn);
            return new ToolRegistry.ToolResult("ok", null, true);
        });

        GatedModel first = GatedModel.ungated(
                AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("1").name("law_search").arguments("{}").build())),
                AiMessage.from("<final>第一轮收尾</final>"));
        GatedModel second = GatedModel.ungated(AiMessage.from("<final>第二轮收尾</final>"));

        Thread t1 = runAsync("帮我看看上市的问题", first);
        assertTrue(firstToolRunning.await(10, TimeUnit.SECONDS), "第一轮没进入工具执行");

        // 第一轮的工具还在跑，第二轮起跑并跑完——它命中的是另一个 skill
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(second);
        orchestrator.handleUserMessage(request("帮我做一下脱敏"), 7L);

        firstToolMayReturn.countDown();
        join(t1);

        assertEquals(2, first.offeredToolsPerRound.size(), "第一轮应当跑满两轮 generate");
        List<String> secondRoundTools = first.offeredToolsPerRound.get(1);
        assertTrue(secondRoundTools.contains("law_search"),
                "第一轮的可见工具被第二轮的 skill 换掉了（拿到的是：" + secondRoundTools + "）");
        assertFalse(secondRoundTools.contains("mask_text"),
                "第一轮不该看到第二轮 skill 的工具：" + secondRoundTools);
        assertTrue(second.offeredToolsPerRound.get(0).contains("mask_text"),
                "第二轮自己该看到 mask_text：" + second.offeredToolsPerRound);
        assertFalse(second.offeredToolsPerRound.get(0).contains("law_search"),
                "第二轮不该看到第一轮 skill 的工具：" + second.offeredToolsPerRound);
    }

    // =====================================================================================
    // ④ 旧轮次的流式增量：被取代之后一个字节都不许再打到 emitter 上
    // =====================================================================================

    @Test
    @DisplayName("旧轮次被取代后继续吐 token：text_delta / reasoning_delta / doc_stream_data 都不再出现，新一轮照常")
    void supersededTurnStopsStreamingDeltas() throws Exception {
        // 编辑器流式写入打开：doc_stream_data / wps_stream_data 这条增量通道也要一并守住
        when(editorBridge.isStreamingMode(CONV)).thenReturn(true);

        CountDownLatch oldStreaming = new CountDownLatch(1);
        CountDownLatch oldMayContinue = new CountDownLatch(1);

        // 旧轮次：先吐一段（此时它还是当前轮次），被取代之后再吐正文与思考各一段
        GatedModel oldTurn = new GatedModel(
                List.of(AiMessage.from("OLD-BEFORE-TAKEOVER")), 0, oldStreaming, oldMayContinue,
                List.of("OLD-AFTER-TAKEOVER"), "OLD-REASONING-AFTER");
        GatedModel newTurn = GatedModel.ungated(AiMessage.from("NEW-TURN-BODY"));

        Thread t1 = runAsync("旧的问题", oldTurn);
        assertTrue(oldStreaming.await(10, TimeUnit.SECONDS), "旧轮次没进入流式");

        // 新一轮起跑并跑完：从这一刻起旧轮次不再是当前轮次
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(newTurn);
        orchestrator.handleUserMessage(request("新一轮的问题"), 7L);

        oldMayContinue.countDown();
        join(t1);

        String textDeltas = payloadsOf("text_delta");
        String reasoningDeltas = payloadsOf("reasoning_delta");
        String docStream = payloadsOf("doc_stream_data") + payloadsOf("wps_stream_data");

        // 取代之前旧轮次流出的那一段是合法的，不该被误伤
        assertTrue(textDeltas.contains("OLD-BEFORE-TAKEOVER"),
                "取代之前旧轮次的正文也被挡掉了（闸下过头）：" + textDeltas);
        // 新一轮自己的增量照常
        assertTrue(textDeltas.contains("NEW-TURN-BODY"),
                "新一轮的正文没打到 emitter 上：" + textDeltas);

        // 取代之后旧轮次的一切增量都必须静默——否则它的 token 会混进新一轮的气泡
        assertFalse(textDeltas.contains("OLD-AFTER-TAKEOVER"),
                "旧轮次被取代后仍在往 emitter 发 text_delta，token 混进了新一轮的气泡：" + textDeltas);
        assertFalse(reasoningDeltas.contains("OLD-REASONING-AFTER"),
                "旧轮次被取代后仍在往 emitter 发 reasoning_delta：" + reasoningDeltas);
        assertFalse(docStream.contains("OLD-AFTER-TAKEOVER"),
                "旧轮次被取代后仍在往编辑器流式写入，正文会插进新一轮正在写的文档：" + docStream);
    }

    // =====================================================================================
    // ⑤ chat 返回之后、异步体起跑之前发来的 cancel：不许落空
    // =====================================================================================

    @Test
    @DisplayName("chat 返回后、循环起跑前发来的取消：该轮次起跑即看到取消标志，模型一次都不调")
    void cancelBeforeTurnBodyStartsIsNotLost() throws Exception {
        // 卡住线程池：handleUserMessage 提交完就返回（模拟控制器线程返回 200），
        // 循环本体要等 mayStart 放行才起跑——取消正好落在这个窗口里
        CountDownLatch mayStart = new CountDownLatch(1);
        List<Thread> bodies = new CopyOnWriteArrayList<>();
        orchestrator.setTurnExecutor(task -> {
            Thread t = new Thread(() -> {
                awaitQuietly(mayStart);
                task.run();
            }, "turn-body");
            bodies.add(t);
            t.start();
        });

        GatedModel model = GatedModel.ungated(AiMessage.from("<final>不该被生成出来的回答</final>"));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);

        orchestrator.handleUserMessage(request("发出去就后悔的问题"), 7L);

        // 控制器已经返回，循环本体一行都还没跑。此刻的取消必须能找到本轮。
        orchestrator.setCancelled(CONV);
        assertTrue(orchestrator.isCancelled(CONV),
                "取消落空了：chat 已经返回，activeRuns 里却查不到这一轮");

        mayStart.countDown();
        for (Thread t : bodies) join(t);

        assertEquals(0, model.calls,
                "取消落空了：轮次起跑后仍然调用了模型（用户点了停止却还在烧 token）");
        assertEquals(AgentRunStateService.RunStatus.CANCELLED, runState.get(CONV).status(),
                "本轮没有走取消路径收尾");
        assertTrue(payloadsOf("cancelled").contains("用户已停止生成")
                        || payloadsOf("cancelled").contains("stopped by the user"),
                "没有向前端推送取消事件：" + payloadsOf("cancelled"));
    }

    /** 把某个事件名下所有载荷拼起来，便于做「出现/不出现」的判定。 */
    private String payloadsOf(String eventName) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : sseEvents) {
            if (eventName.equals(e[0])) sb.append(e[1]).append('\n');
        }
        return sb.toString();
    }
}
