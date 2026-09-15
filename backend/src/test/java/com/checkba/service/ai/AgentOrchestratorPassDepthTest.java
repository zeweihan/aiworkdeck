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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Productive Agent work has no arbitrary 30/120-turn stop.
 * This runs the real loop through more than 100 changing tool rounds before a final answer.
 */
class AgentOrchestratorPassDepthTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private OfficePassStateStore passStateStore;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 121 productive tool rounds, then a final answer. */
    private static final class LoopingModel implements StreamingChatLanguageModel {
        final AtomicInteger calls = new AtomicInteger();
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            int n = calls.incrementAndGet();
            if (n <= 1001) {
                handler.onComplete(Response.from(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("t" + n).name("office_pass_step")
                        .arguments("{\"editsJson\":\"[]\",\"round\":" + n + "}").build()))));
            } else {
                handler.onNext("完成");
                handler.onComplete(Response.from(AiMessage.from("完成")));
                finished.countDown();
            }
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

        messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("请校对全文"))));

        toolRegistry = mock(ToolRegistry.class);
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
    @DisplayName("普通任务可连续完成 1001 个有进展的工具轮次且不耗尽 Java 栈")
    void productiveRunExceedsOneThousandRounds() throws Exception {
        LoopingModel model = run("conv-nopass");

        assertTrue(model.finished.await(20, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1002, model.calls.get());
        assertTrue(String.valueOf(bubbleEndData()).contains("finished"));
    }

    @Test
    void recoveryAndDurableToolBuffersAreBoundedAndMarkTheDroppedPrefix() {
        AgentOrchestrator.RunGuard guard = new AgentOrchestrator.RunGuard("conv-buffer", "run-buffer", 1L);
        guard.appendStream("old".repeat(AgentOrchestrator.STREAM_RECOVERY_LIMIT));
        guard.appendStream("LATEST_STREAM");
        assertTrue(guard.streamSnapshot().contains(AgentOrchestrator.STREAM_TRUNCATED_MARKER));
        assertTrue(guard.streamSnapshot().endsWith("LATEST_STREAM"));
        assertTrue(guard.streamSnapshot().length() <= AgentOrchestrator.STREAM_RECOVERY_LIMIT);

        StringBuilder executionLog = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            AgentOrchestrator.appendBoundedExecutionLog(executionLog,
                    "<process name=\"test\"><tool_output>" + i + ":" + "x".repeat(4000)
                            + "</tool_output></process>\n");
        }
        assertTrue(executionLog.toString().contains(AgentOrchestrator.EXECUTION_LOG_TRUNCATED_MARKER));
        assertTrue(executionLog.toString().contains("199:"));
        assertTrue(executionLog.length() <= AgentOrchestrator.EXECUTION_LOG_LIMIT);
    }

    @Test
    void persistenceTruncationDoesNotTruncateTheToolResultGivenBackToTheModel() {
        String full = "x".repeat(20_000) + "FULL_TAIL";
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult(full, null, true));
        java.util.concurrent.atomic.AtomicBoolean modelSawTail = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        StreamingChatLanguageModel model = new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                if (calls.incrementAndGet() == 1) {
                    handler.onComplete(Response.from(AiMessage.from(List.of(ToolExecutionRequest.builder()
                            .id("large").name("read").arguments("{}").build()))));
                    return;
                }
                modelSawTail.set(messages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast).anyMatch(m -> m.text().endsWith("FULL_TAIL")));
                handler.onNext("done");
                handler.onComplete(Response.from(AiMessage.from("done")));
            }

            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                           StreamingResponseHandler<AiMessage> handler) {
                generate(messages, handler);
            }
        };
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-full-tool-result");
        request.setMessage("read");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);

        assertTrue(modelSawTail.get(), "the model must receive the complete tool observation");
        org.mockito.ArgumentCaptor<String> saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(messageService, atLeastOnce()).upsertAssistantMessage(any(), any(), any(), any(), saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(v -> v.contains("...(truncated)") || v.contains("...(截断)")));
        assertTrue(saved.getAllValues().stream().noneMatch(v -> v.contains("FULL_TAIL")));
    }
}
