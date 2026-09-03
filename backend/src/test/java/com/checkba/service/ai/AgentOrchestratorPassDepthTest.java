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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 整篇过卷的步数预算（dev-board#422）。
 *
 * <p>MAX_LOOP_DEPTH=30 是给「常规多步任务」定的。分段过卷是刻意的多步推进：
 * 一块一步，块数由文档长度决定。恒 30 会让一份长文档的过卷跑到一半被迫暂停——
 * 这正是 #419 要根治的那种「一路正在操作文档到撞上限」的形态换了个位置复发。
 * 所以过卷进行中把预算抬到 {@code min(30 + total, 120)}，没有过卷时一切照旧。
 *
 * <p>本用例走真实 runLoop（不是只测那个算式）：把深度规则改回恒 30 就会转红。
 */
class AgentOrchestratorPassDepthTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private OfficePassStateStore passStateStore;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 永远回一个工具调用的模型：让循环一路跑到步数预算耗尽。参数每轮不同，避开打转干预。 */
    private static final class LoopingModel implements StreamingChatLanguageModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            int n = calls.incrementAndGet();
            handler.onComplete(Response.from(AiMessage.from(List.of(ToolExecutionRequest.builder()
                    .id("t" + n).name("office_pass_step")
                    .arguments("{\"editsJson\":\"[]\",\"round\":" + n + "}").build()))));
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            generate(messages, handler);
        }
    }

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("请校对全文"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenAnswer(inv -> new ToolRegistry.ToolResult("{\"pass\":{\"done\":false}}", null, true));

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        AgentRunStateService runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        passStateStore = new OfficePassStateStore();
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                passStateStore);
    }

    private LoopingModel run(String conversationId) {
        LoopingModel model = new LoopingModel();
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("请校对全文");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    private String bubbleEndData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    @Test
    @DisplayName("没有过卷：步数预算仍是 30（depth 0..30 各调一次模型，第 31 步暂停）")
    void withoutPassBudgetStaysAtThirty() {
        LoopingModel model = run("conv-nopass");

        assertEquals(31, model.calls.get(), "无过卷时预算恒为 30 步");
        assertTrue(String.valueOf(bubbleEndData()).contains("max_depth"), "撞预算应按 paused 收尾");
    }

    @Test
    @DisplayName("过卷进行中：预算抬到 min(30 + 块数, 120)")
    void passInProgressRaisesBudgetByChunkCount() {
        passStateStore.start("conv-pass", List.of(
                new OfficePassChunker.Chunk(1, 10),
                new OfficePassChunker.Chunk(11, 20),
                new OfficePassChunker.Chunk(21, 30),
                new OfficePassChunker.Chunk(31, 40),
                new OfficePassChunker.Chunk(41, 50)), "hash-a");

        LoopingModel model = run("conv-pass");

        assertEquals(36, model.calls.get(), "5 块过卷 → 预算 35 步");
        assertTrue(String.valueOf(bubbleEndData()).contains("max_depth"));
    }

    @Test
    @DisplayName("步数预算的算式：无过卷 30；有过卷 min(30+total,120)")
    void budgetFormula() {
        assertEquals(30, AgentOrchestrator.maxLoopDepthFor(passStateStore, "conv-none"));

        passStateStore.start("conv-a", List.of(new OfficePassChunker.Chunk(1, 10)), "h");
        assertEquals(31, AgentOrchestrator.maxLoopDepthFor(passStateStore, "conv-a"));

        List<OfficePassChunker.Chunk> sixty = new ArrayList<>();
        for (int i = 0; i < 60; i++) sixty.add(new OfficePassChunker.Chunk(i * 10 + 1, i * 10 + 10));
        passStateStore.start("conv-b", sixty, "h");
        assertEquals(90, AgentOrchestrator.maxLoopDepthFor(passStateStore, "conv-b"));

        // 60 块是切块器的上限，但算式本身也要在更大的输入上收敛到 120
        List<OfficePassChunker.Chunk> huge = new ArrayList<>();
        for (int i = 0; i < 200; i++) huge.add(new OfficePassChunker.Chunk(i * 10 + 1, i * 10 + 10));
        passStateStore.start("conv-c", huge, "h");
        assertEquals(120, AgentOrchestrator.maxLoopDepthFor(passStateStore, "conv-c"));
    }
}
