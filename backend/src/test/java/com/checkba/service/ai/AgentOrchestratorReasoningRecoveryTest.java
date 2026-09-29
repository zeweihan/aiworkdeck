// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
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
 * dev-board#1060：进行中的对话切走再切回，思考记录不能没了；刷新后看历史，思考也要在。
 *
 * <p>两条契约：① 流式进行中 {@code getRecoveryPayload} 里按序带着思考、编排器补发的工具过程
 * 与服务端时间戳；② 收尾落库时思考全文随 ASSISTANT 行写进 reasoning 列，每次调模型一块，
 * anchor = 那块开始前已执行的工具过程数。没有思考的轮次仍走五参落库（见既有各用例）。
 */
class AgentOrchestratorReasoningRecoveryTest {

    private static final String MODEL = "anthropic/claude-sonnet-5";

    private ChatModelFactory chatModelFactory;
    private ProjectAiMessageService messageService;
    private AgentRunStateService runState;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;
    private SseEmitterService sse;
    private ContextAssemblerService assembler;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        sse = mock(SseEmitterService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        // 返回 2 条：跳过首轮标题生成的异步分支，本用例只关心终止路径
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("整理一下这份合同"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        rebuildOrchestratorWith(toolRegistry);
    }

    /** 只有工具注册表因用例而异，其余依赖与 setUp 完全一致。 */
    private void rebuildOrchestratorWith(ToolRegistry toolRegistry) {
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
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private void run(String conversationId) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("整理一下这份合同");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
    }


    @Test
    @DisplayName("思考 → 工具 → 再思考：中途恢复载荷按序带全，收尾落库两块思考且 anchor 分别为 0 / 1")
    void reasoningIsReplayableMidStreamAndPersistedPerRound() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of(
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name("read_document").description("读文档").build()));
        when(toolRegistry.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("合同正文若干", null, true));
        rebuildOrchestratorWith(toolRegistry);

        String[] midStream = new String[1];
        long before = System.currentTimeMillis();
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(
                new StreamingChatLanguageModel() {
                    int calls;
                    @Override
                    public void generate(List<ChatMessage> messages,
                                         dev.langchain4j.model.StreamingResponseHandler
                                                 <dev.langchain4j.data.message.AiMessage> handler) {
                        generate(messages, List.of(), handler);
                    }
                    @Override
                    public void generate(List<ChatMessage> messages,
                                         List<dev.langchain4j.agent.tool.ToolSpecification> tools,
                                         dev.langchain4j.model.StreamingResponseHandler
                                                 <dev.langchain4j.data.message.AiMessage> handler) {
                        ReasoningStreamingHandler r = (ReasoningStreamingHandler) handler;
                        if (calls++ == 0) {
                            r.onReasoning("先读合同。");
                            handler.onComplete(dev.langchain4j.model.output.Response.from(
                                    dev.langchain4j.data.message.AiMessage.from(List.of(
                                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                                    .id("1").name("read_document")
                                                    .arguments("{\"fileId\":1}").build()))));
                            return;
                        }
                        r.onReasoning("读完了，");
                        r.onReasoning("开始回答。");
                        // 用户此刻切走再切回：/connect 取到的恢复载荷
                        midStream[0] = orchestrator.getRecoveryPayload("conv-reasoning", false);
                        handler.onNext("<final>答案</final>");
                        handler.onComplete(dev.langchain4j.model.output.Response.from(
                                dev.langchain4j.data.message.AiMessage.from("<final>答案</final>")));
                    }
                });

        run("conv-reasoning");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertTrue(midStream[0] != null, "进行中的轮次必须给得出恢复载荷");
        com.fasterxml.jackson.databind.JsonNode payload = mapper.readTree(midStream[0]);
        assertEquals("", payload.get("content").asText(), "旧的 content 字段原义不变：只有模型 token，思考不进去");
        assertTrue(payload.get("startedAt").asLong() >= before, "带本段开始时间");
        assertTrue(payload.get("serverNow").asLong() >= payload.get("startedAt").asLong(), "带服务端当前时间");
        com.fasterxml.jackson.databind.JsonNode events = payload.get("events");
        List<String> kinds = new ArrayList<>();
        events.forEach(e -> kinds.add(e.get("type").asText()));
        assertEquals("reasoning", kinds.get(0), "第一段是首轮思考：" + kinds);
        assertEquals("先读合同。", events.get(0).get("content").asText());
        assertEquals("reasoning", kinds.get(kinds.size() - 1), "最后一段是第二轮思考：" + kinds);
        assertEquals("读完了，开始回答。", events.get(kinds.size() - 1).get("content").asText(),
                "同一段里连续的思考增量合成一段");
        assertTrue(events.get(kinds.size() - 1).has("lastAt"), "思考段带最后一次增量的时间");
        boolean toolReplayed = false;
        for (com.fasterxml.jackson.databind.JsonNode e : events) {
            if ("text".equals(e.get("type").asText()) && e.get("content").asText().contains("read_document")) {
                toolReplayed = true;
            }
        }
        assertTrue(toolReplayed, "编排器补发的工具过程也要进回放，否则切回来过程卡全没了：" + midStream[0]);

        ArgumentCaptor<String> reasoning = ArgumentCaptor.forClass(String.class);
        verify(messageService, org.mockito.Mockito.atLeastOnce()).upsertAssistantMessage(
                eq("1"), eq(7L), eq("conv-reasoning"), any(), any(), reasoning.capture());
        com.fasterxml.jackson.databind.JsonNode blocks = mapper.readTree(reasoning.getValue());
        assertEquals(2, blocks.size(), "每次调模型一块，不合成一坨：" + reasoning.getValue());
        assertEquals("先读合同。", blocks.get(0).get("text").asText());
        assertEquals(0, blocks.get(0).get("anchor").asInt());
        assertEquals("读完了，开始回答。", blocks.get(1).get("text").asText());
        assertEquals(1, blocks.get(1).get("anchor").asInt(), "第二块思考发生在第 1 个工具过程之后");
        assertTrue(blocks.get(1).get("endedAt").asLong() >= blocks.get(1).get("startedAt").asLong());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-reasoning").status());
    }

    @Test
    @DisplayName("没有活跃轮次：不强制时无载荷，强制时退回只有 content 的空载荷")
    void noActiveRun() {
        assertEquals(null, orchestrator.getRecoveryPayload("conv-none", false));
        assertEquals("{\"content\":\"\"}", orchestrator.getRecoveryPayload("conv-none", true));
    }
}
