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
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具空输出与 onComplete 异常路径的两条不变式。
 *
 * <p>病灶一：工具返回空串时 {@code ToolExecutionResultMessage.from} 的
 * {@code ensureNotBlank(text, "text")} 直接抛异常，整轮对话被掀翻——用户看到的是
 * 「Callback Error: text cannot be null or blank」。任何工具返回空都不允许掀翻整轮。
 *
 * <p>病灶二：onComplete 的 catch 只发 SSE、不落库，于是异常终止的那一轮在历史里
 * 一个字都没有（工具跑过的过程也一起没了）——「历史对话吃消息」。
 * 对照 handleStreamErrorTerminal 与 handleCancellation：两者都存了部分内容。
 */
class AgentOrchestratorBlankToolOutputTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private AgentRunStateService runState;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private TodoListService todoListService;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 按脚本逐轮吐内容的模型，同时留下最后一轮收到的完整消息栈 */
    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();
        volatile List<ChatMessage> lastMessages = List.of();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            lastMessages = new ArrayList<>(messages);
            int idx = calls.getAndIncrement();
            AiMessage msg = script.get(Math.min(idx, script.size() - 1));
            if (msg.text() != null && !msg.text().isEmpty()) {
                handler.onNext(msg.text());
            }
            handler.onComplete(Response.from(msg));
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

        messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("读一下这份合同"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        todoListService = mock(TodoListService.class);
        AiContextProperties contextProperties = new AiContextProperties();
        runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), todoListService,
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class));
    }

    private ScriptModel run(String conversationId, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("读一下这份合同");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    private static AiMessage readDocumentCall() {
        return AiMessage.from(List.of(ToolExecutionRequest.builder()
                .id("t1").name("read_document").arguments("{\"fileId\":\"12\"}").build()));
    }

    private String bubbleEndData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    private String eventData(String event) {
        for (int i = 0; i < sseEvents.size(); i++) {
            if (event.equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    @Test
    @DisplayName("工具返回空串：不掀翻整轮，按 FAILURE 回喂模型，对话照常收尾")
    void blankToolOutputDoesNotBreakTheTurn() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("", null, true));

        ScriptModel model = run("conv-blank", readDocumentCall(),
                AiMessage.from("<final>这份文档读不出正文。</final>"));

        assertFalse(sseEvents.contains("error"),
                "空输出不许把整轮打掉（langchain4j 的 ensureNotBlank）：" + sseEvents);
        assertEquals(2, model.calls.get(), "空结果也要回喂模型继续下一轮");
        assertEquals("{\"status\":\"finished\"}", bubbleEndData());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-blank").status());

        ToolExecutionResultMessage toolResult = model.lastMessages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElse(null);
        assertNotNull(toolResult, "工具结果必须进上下文，否则 tool_calls 无配对结果会让通道 400");
        assertFalse(toolResult.text().isBlank(), "空白正文正是 ensureNotBlank 抛异常的原因");
        assertTrue(toolResult.text().contains("no output"),
                "要给模型一句能据以行动的说明，实际是：" + toolResult.text());

        assertTrue(sseData.stream().anyMatch(d -> d != null && d.contains("FAILURE")),
                "空输出按失败处理，才能进连续失败纠正回路：" + sseData);
    }

    @Test
    @DisplayName("onComplete 异常：执行日志与错误摘要必须落库，历史里看得见「执行中断」")
    void onCompleteFailurePersistsExecutionLog() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("合同正文…", null, true));
        // 工具跑完之后、增量保存之前炸掉：模拟 onComplete 回调里的内部一致性错误
        when(todoListService.reminder(any())).thenThrow(new IllegalStateException("boom"));

        run("conv-crash", readDocumentCall(), AiMessage.from("<final>不会走到</final>"));

        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-crash").status());

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(messageService, atLeastOnce())
                .upsertAssistantMessage(any(), any(), any(), any(), saved.capture());
        String content = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertTrue(content.contains("<tool_output"),
                "崩溃轮的执行过程也要能回放，实际落库的是：" + content);
        assertTrue(content.contains("[生成出错，已中断]"),
                "历史里要看得出这一轮是断掉的，实际落库的是：" + content);

        String err = eventData("error");
        assertNotNull(err, "仍要给前端错误事件：" + sseEvents);
        assertTrue(err.contains(LlmErrorClassifier.INTERNAL_ERROR_MARKER),
                "内部错误要带机器可读标记，前端才能换成人话，实际是：" + err);
    }
}
