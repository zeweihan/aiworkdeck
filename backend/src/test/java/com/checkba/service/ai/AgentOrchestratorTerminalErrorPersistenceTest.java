package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountException;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终止 catch 块的落库：用户消息已经落了，助手侧不补一条，刷新页面后这一轮就只剩用户
 * 自己的问题——看起来像 AI 完全没理他，而不是出过错。
 *
 * <p>守两条：AccountException（未分配 AI 额度这类，新用户最常撞）与兜底 Exception，
 * 各自都必须落一条 ASSISTANT，且内容与 SSE 推出去的是同一串。
 */
class AgentOrchestratorTerminalErrorPersistenceTest {

    private static final String MODEL = "anthropic/claude-sonnet-5";

    private ChatModelFactory chatModelFactory;
    private ProjectAiMessageService messageService;
    private AgentRunStateService runState;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        SseEmitterService sse = mock(SseEmitterService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        // 返回 2 条：跳过首轮标题生成的异步分支，本用例只关心终止路径
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
        failoverProperties.setModels(List.of());
        AiContextProperties contextProperties = new AiContextProperties();
        RunLoopCompactor compactor =
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties));

        runState = new AgentRunStateService(mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState, mock(com.checkba.version.WorkSessionService.class),
                failoverProperties, compactor,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class));
    }

    private void run(String conversationId) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("整理一下这份合同");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
    }

    /** 取本轮唯一那条 error 事件的载荷 */
    private String errorPayload() {
        int idx = sseEvents.indexOf("error");
        assertTrue(idx >= 0, "应给用户推了 error 事件：" + sseEvents);
        return sseData.get(idx);
    }

    /** 取落库的那条 ASSISTANT 正文 */
    private String persistedAssistantText(String conversationId) {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService, times(1)).upsertAssistantMessage(
                eq("1"), eq(7L), eq(conversationId), any(), content.capture());
        return content.getValue();
    }

    @Test
    @DisplayName("未分配 AI 额度：落一条 ASSISTANT，原文即推给用户的中文提示")
    void persistsAssistantMessageOnAccountException() {
        String notice = "请先在官网账户页分配 AI 额度";
        when(chatModelFactory.getStreamingChatModel(MODEL))
                .thenThrow(new AccountException(AccountException.Kind.CONFLICT, notice));

        run("conv-account");

        assertEquals(notice, errorPayload(), "账户类失败原样透出中文文案，不加前缀");
        assertEquals(notice, persistedAssistantText("conv-account"),
                "历史里必须留下这一轮的回应，且与 SSE 推出去的一致");
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-account").status());
    }

    @Test
    @DisplayName("兜底异常：落一条 ASSISTANT，内容与 SSE 推出去的同一串")
    void persistsAssistantMessageOnGenericException() {
        when(chatModelFactory.getStreamingChatModel(MODEL))
                .thenThrow(new RuntimeException("boom"));

        run("conv-generic");

        assertEquals("Internal Error: boom", errorPayload());
        assertEquals(errorPayload(), persistedAssistantText("conv-generic"),
                "落库内容必须与 SSE 发出的错误文本一致");
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-generic").status());
    }
}
