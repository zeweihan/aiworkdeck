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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code bubble_end.documentEdited}：本轮有没有成功调用过文档编辑工具（dev-board#728）。
 *
 * <p>前端据此决定「用到文档」那组手动操作（插入 / 替换选区 / 导出 Word）还要不要出：
 * AI 已经把内容写进文档了，再请用户手动插入一遍是多余的一步。所以这个字段错在哪个方向
 * 后果都具体：
 * <ul>
 *   <li>该 true 报成 false：用户被请去把 AI 刚写进去的内容再插一遍（这张卡要修的病）；</li>
 *   <li>该 false 报成 true：AI 只是答了个问题，用户却找不到把答案放进文书的入口。</li>
 * </ul>
 *
 * <p>守四件事：① 文档编辑工具成功后为 true；② 纯问答轮次为 false；③ 工具<b>失败</b>不算数
 * （什么都没写进文档，藏掉按钮等于把唯一的补救入口一起藏了）；④ 非文档工具不算数。
 * 另外钉住「所有 bubble_end 发送点都带这个字段」——六个发送点漏一个，前端在那条路径上
 * 读到 undefined，按「没编辑过」把按钮放出来，而那恰恰是编辑最多的几条路径之一。
 */
class AgentOrchestratorDocumentEditedFlagTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private ToolRegistry toolRegistry;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 按脚本逐轮吐内容的模型（与 AgentOrchestratorQuestionStopTest 同款） */
    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
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

        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("把第三条的违约金改成 10%"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("ok", null, true));

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class),
                new AgentRunStateService(
                        mock(com.checkba.repository.AgentRunRecordRepository.class),
                        mock(com.checkba.service.telemetry.TelemetryTurnTracker.class)),
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private void run(String conversationId, AiMessage... script) {
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new ScriptModel(List.of(script)));
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("把第三条的违约金改成 10%");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
    }

    private String bubbleEndData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    private static AiMessage callsTool(String toolName) {
        return AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("1").name(toolName).arguments("{}").build()));
    }

    @Test
    @DisplayName("调用文档编辑工具并成功：documentEdited=true —— 内容已经写进文档，不必再请用户手动插入")
    void documentEditingToolMarksTheTurn() {
        run("conv-doc-edit",
                callsTool("doc_find_replace"),
                AiMessage.from("<final>第三条的违约金已改为 10%。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":true}", bubbleEndData());
    }

    @Test
    @DisplayName("纯问答轮次：documentEdited=false —— 「用到文档」正是这种回答需要的出口")
    void plainAnswerTurnIsNotMarked() {
        run("conv-plain", AiMessage.from("<final>违约金上限一般按合同总额的 20% 掌握。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":false}", bubbleEndData());
    }

    @Test
    @DisplayName("文档编辑工具失败：不算改过 —— 什么都没写进去，藏掉按钮等于把补救入口一起藏了")
    void failedDocumentToolDoesNotCount() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("Error: 未找到匹配的段落", null, true));

        run("conv-doc-fail",
                callsTool("doc_find_replace"),
                AiMessage.from("<final>没找到第三条，请确认文档。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":false}", bubbleEndData());
    }

    @Test
    @DisplayName("非文档工具（检索/读文件）成功：不算改过")
    void nonDocumentToolDoesNotCount() {
        run("conv-search",
                callsTool("search_project_files"),
                AiMessage.from("<final>项目里有三份相关合同。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":false}", bubbleEndData());
    }

    @Test
    @DisplayName("sheet_/slide_ 与 doc_ 同一判据：表格、演示的编辑一样算改过")
    void sheetAndSlideToolsCountToo() {
        run("conv-sheet", callsTool("sheet_write_cells"), AiMessage.from("<final>已填好。</final>"));
        assertEquals("{\"status\":\"finished\",\"documentEdited\":true}", bubbleEndData());

        sseEvents.clear();
        sseData.clear();
        run("conv-slide", callsTool("slide_set_shape_text"), AiMessage.from("<final>已改好。</final>"));
        assertEquals("{\"status\":\"finished\",\"documentEdited\":true}", bubbleEndData());
    }

    @Test
    @DisplayName("非 finished 的收尾也要带这个字段：反问停机那条路同样可能已经改过文档")
    void nonFinishedTerminalsCarryTheFlagToo() {
        run("conv-doc-question",
                AiMessage.from("<question>第三条还是第四条？</question>", List.of(
                        ToolExecutionRequest.builder().id("1").name("doc_find_replace").arguments("{}").build())));

        assertEquals("{\"status\":\"awaiting_input\",\"documentEdited\":true}", bubbleEndData());
        assertFalse(sseEvents.contains("error"));
    }

    @Test
    @DisplayName("只读的文档工具不算改过：先读文档再起草条款，那一轮正是最该出按钮的")
    void readOnlyDocumentToolDoesNotCount() {
        run("conv-doc-read",
                callsTool("doc_get_document_text"),
                AiMessage.from("<final>这份合同的付款期限是 30 日。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":false}", bubbleEndData());
    }

    @Test
    @DisplayName("先读后写：同一轮里读过又写过，仍然算改过（置位后不清零）")
    void readThenWriteStillCounts() {
        AiMessage readThenWrite = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("1").name("doc_get_outline").arguments("{}").build(),
                ToolExecutionRequest.builder().id("2").name("doc_insert_at_cursor").arguments("{}").build(),
                ToolExecutionRequest.builder().id("3").name("doc_get_document_text").arguments("{}").build()));

        run("conv-doc-rw", readThenWrite, AiMessage.from("<final>条款已写入。</final>"));

        assertEquals("{\"status\":\"finished\",\"documentEdited\":true}", bubbleEndData());
    }

    @Test
    @DisplayName("判据只此一处：编排器用写入判据，工具可见性仍用宽前缀判据")
    void orchestratorUsesTheWritingPredicate() {
        // 归类的全量对拍在 DocumentWritingToolClassificationTest；这里只钉住
        // 「编排器用的是哪一个」——两个判据分开正是 dev-board#728 的修正点。
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_find_replace"));
        assertFalse(ClientCapabilityService.isDocumentWritingTool("doc_get_document_text"));
        assertTrue(ClientCapabilityService.isLowaTool("doc_get_document_text"),
                "读取工具仍然需要 LOWA，可见性判据不能跟着收窄");
        assertFalse(ClientCapabilityService.isDocumentWritingTool("search_project_files"));
        assertFalse(ClientCapabilityService.isDocumentWritingTool("office_replace_batch"));
    }
}
