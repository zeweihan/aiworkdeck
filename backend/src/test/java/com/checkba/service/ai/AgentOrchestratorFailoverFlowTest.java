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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障转移的完整链路：主模型报错 → 换备选 → 本轮继续跑完。
 *
 * <p>候选选择逻辑由 AgentOrchestratorFailoverTest 单测覆盖，这里守的是接线：
 * setOnError 真的会调工厂拿备选模型、真的会把本轮重放到底、真的给用户发了切换提示，
 * 且不可重试的 FATAL 错误不会白白多花一次调用。
 */
class AgentOrchestratorFailoverFlowTest {

    /** 主模型故意选国际档：地域拒绝那条用例要的就是「境内打国际模型」这个形态 */
    private static final String PRIMARY = "anthropic/claude-sonnet-5";
    /** 备选是区域无关（Region.GLOBAL）模型：地域拒绝时唯一能救回本轮的一类候选 */
    private static final String BACKUP = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private AgentRunStateService runState;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 只会报错的模型（模拟 OpenRouter 把下线模型返成 404） */
    private record FailingModel(RuntimeException error) implements StreamingChatLanguageModel {
        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onError(error);
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            handler.onError(error);
        }
    }

    /** 正常吐一段文本就收尾的模型 */
    private record HealthyModel(String text) implements StreamingChatLanguageModel {
        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onNext(text);
            handler.onComplete(Response.from(AiMessage.from(text)));
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
        sse = mock(SseEmitterService.class);
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
                        SystemMessage.from("system"), UserMessage.from("整理一下这份合同"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiFailoverProperties failoverProperties = new AiFailoverProperties();
        failoverProperties.setModels(List.of(BACKUP));
        AiContextProperties contextProperties = new AiContextProperties();
        RunLoopCompactor compactor =
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties));

        runState = new AgentRunStateService(mock(com.checkba.repository.AgentRunRecordRepository.class), mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState, mock(com.checkba.version.WorkSessionService.class),
                failoverProperties, compactor,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private void run(String conversationId) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("整理一下这份合同");
        request.setModel(PRIMARY);
        orchestrator.handleUserMessage(request, 7L);
    }

    private String allText() {
        return String.join("\n", sseData);
    }

    @Test
    @DisplayName("模型下线 404：立刻换备选把本轮跑完，用户看到切换提示而不是报错")
    void switchesToBackupOnModelOffline() {
        when(chatModelFactory.getStreamingChatModel(PRIMARY))
                .thenReturn(new FailingModel(new RuntimeException(
                        "status code: 404 - No endpoints found for " + PRIMARY)));
        when(chatModelFactory.getStreamingChatModel(BACKUP))
                .thenReturn(new HealthyModel("好的，我先读一下合同。"));

        run("conv-failover");

        verify(chatModelFactory).getStreamingChatModel(BACKUP);
        assertTrue(allText().contains("已自动切换到备用模型"), "必须明确告诉用户换了模型：" + allText());
        assertTrue(allText().contains(BACKUP), "提示里要点名切到了哪个模型");
        assertFalse(sseEvents.contains("error"), "换成功就不该再给用户报错");
        assertTrue(sseEvents.contains("bubble_end"), "本轮应正常收尾");
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-failover").status());
    }

    @Test
    @DisplayName("地域 403：换到区域无关模型把本轮跑完，提示里点名原因与新模型")
    void switchesToRegionAgnosticModelOnRegionBlock() {
        when(chatModelFactory.getStreamingChatModel(PRIMARY))
                .thenReturn(new FailingModel(new RuntimeException(
                        "status code: 403 - This model is not available in your region")));
        when(chatModelFactory.getStreamingChatModel(BACKUP))
                .thenReturn(new HealthyModel("好的，我先读一下合同。"));

        run("conv-region");

        verify(chatModelFactory).getStreamingChatModel(BACKUP);
        assertTrue(allText().contains("在当前网络环境不可用"), "要说清原模型为什么不能用：" + allText());
        assertTrue(allText().contains(BACKUP), "要点名切到了哪个模型");
        assertFalse(sseEvents.contains("error"), "换成功就不该再给用户报错");
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-region").status());
    }

    @Test
    @DisplayName("地域 403 且备选链里全是国际档：终态报错带 AI_REGION_BLOCKED 标记供前端换文案")
    void tagsTerminalErrorWhenNoRegionAgnosticBackupExists() {
        AiFailoverProperties internationalOnly = new AiFailoverProperties();
        internationalOnly.setModels(List.of("google/gemini-3.6-flash"));
        when(chatModelFactory.getStreamingChatModel(PRIMARY))
                .thenReturn(new FailingModel(new RuntimeException(
                        "status code: 403 - This model is not available in your region")));

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-region-dead-end");
        request.setMessage("整理一下这份合同");
        request.setModel(PRIMARY);
        orchestratorWith(internationalOnly).handleUserMessage(request, 7L);

        verify(chatModelFactory, never()).getStreamingChatModel(eq("google/gemini-3.6-flash"));
        assertTrue(sseEvents.contains("error"), "无可用备选时应终态报错");
        assertTrue(allText().contains(LlmErrorClassifier.REGION_BLOCKED_MARKER),
                "错误载荷必须带标记，否则前端只能显示上游英文原文：" + allText());
        assertEquals(AgentRunStateService.RunStatus.ERROR,
                runState.get("conv-region-dead-end").status());
    }

    @Test
    @DisplayName("鉴权类 FATAL 错误不换模型：重放也不会好，白换一次还多花一次调用")
    void doesNotFailoverOnFatalError() {
        when(chatModelFactory.getStreamingChatModel(PRIMARY))
                .thenReturn(new FailingModel(new RuntimeException("status code: 401 - no auth credentials found")));

        run("conv-fatal");

        verify(chatModelFactory, never()).getStreamingChatModel(eq(BACKUP));
        assertTrue(sseEvents.contains("error"), "应终局报错让用户去查 key");
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-fatal").status());
    }

    @Test
    @DisplayName("备选链为空：干净地终态报错，不空转也不乱切")
    void terminatesWhenChainIsEmpty() {
        // 用 404（不重试）避免 5xx 的退避把测试拖成分钟级
        when(chatModelFactory.getStreamingChatModel(PRIMARY))
                .thenReturn(new FailingModel(new RuntimeException("status code: 404 - No endpoints found")));
        when(chatModelFactory.getStreamingChatModel(BACKUP))
                .thenReturn(new HealthyModel("不应该被用到"));

        AiFailoverProperties empty = new AiFailoverProperties();
        empty.setModels(List.of());
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-empty-chain");
        request.setMessage("整理一下这份合同");
        request.setModel(PRIMARY);
        orchestratorWith(empty).handleUserMessage(request, 7L);

        verify(chatModelFactory, never()).getStreamingChatModel(eq(BACKUP));
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-empty-chain").status());
    }

    /** 用另一份故障转移配置重建编排器（其余依赖复用 setUp 里的桩） */
    private AgentOrchestrator orchestratorWith(AiFailoverProperties failoverProperties) {
        AiContextProperties contextProperties = new AiContextProperties();
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(UserMessage.from("整理一下这份合同"))));
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        return new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), failoverProperties,
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }
}
