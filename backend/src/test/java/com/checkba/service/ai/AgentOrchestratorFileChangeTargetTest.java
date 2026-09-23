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
import com.checkba.service.ai.tools.ToolMeta;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * file_change 该指名道姓地报「改了哪份文件」（dev-board#852）。
 *
 * <p>病灶：sheet_* / doc_* / slide_* 直接作用于编辑器里当前打开的那份文档，
 * {@code @ToolMeta} 没有 fileArg，applyToolSideEffects 于是把文件名兜底成字面量
 * {@code "Current Document"} 推给前端。改动卡片按名字去项目里找这份文件，找不到，
 * 在用户刚看着 AI 改完的那份表格上弹一句「未找到文件: Current Document」。
 * 而编排器此刻明明知道活跃文档的 id 与名字（RunGuard.activeFileId / activeFileName）。
 */
class AgentOrchestratorFileChangeTargetTest {

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
    private ProjectFileService projectFileService;

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
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("读一下这份合同"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        todoListService = mock(TodoListService.class);
        projectFileService = mock(ProjectFileService.class);
        AiContextProperties contextProperties = new AiContextProperties();
        runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                projectFileService, mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), todoListService,
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private void run(String conversationId, String activeId, String activeName, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("在末尾新增一行");
        request.setModel(MODEL);
        if (activeId != null) {
            AiAgentController.ContextItem active = new AiAgentController.ContextItem();
            active.setId(activeId);
            active.setName(activeName);
            active.setFileType("xlsx");
            request.setActiveContext(active);
        }
        orchestrator.handleUserMessage(request, 7L);
    }

    private List<String> eventDataAll(String event) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sseEvents.size(); i++) {
            if (event.equals(sseEvents.get(i))) out.add(sseData.get(i));
        }
        return out;
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
    }

    @ToolMeta(displayName = "写入单元格", category = "document", fileEffect = "MODIFIED")
    public void fakeSheetWriteCells() {
    }

    @ToolMeta(displayName = "新建表格文件", category = "document", fileEffect = "ADDED")
    public void fakeSheetCreateFile() {
    }

    @ToolMeta(displayName = "PDF 高亮", category = "pdf", fileEffect = "MODIFIED")
    public void fakePdfHighlight() {
    }

    private ToolRegistry.RegisteredTool tool(String methodName, String toolName) throws Exception {
        java.lang.reflect.Method m = getClass().getDeclaredMethod(methodName);
        return new ToolRegistry.RegisteredTool(this, m,
                ToolSpecification.builder().name(toolName).build(),
                m.getAnnotation(ToolMeta.class), false);
    }

    private static AiMessage call(String name, String args) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder()
                .id("t1").name(name).arguments(args).build()));
    }

    private static final String WRITE_ARGS = "{\"range\":\"A8\",\"valuesJson\":\"[[\\\"x\\\"]]\"}";

    @Test
    @DisplayName("有活跃文档：file_change 报活跃文档的真名与 id，而不是 Current Document")
    void activeDocumentIsNamedWithId() throws Exception {
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已写入 1 个单元格。", tool("fakeSheetWriteCells", "sheet_write_cells"), true));

        run("conv-852-a", "42", "重大合同清单.xlsx",
                call("sheet_write_cells", WRITE_ARGS), AiMessage.from("<final>已新增一行。</final>"));

        List<String> changes = eventDataAll("file_change");
        assertEquals(1, changes.size(), "应恰好一条 file_change：" + sseEvents);
        com.fasterxml.jackson.databind.JsonNode d = json(changes.get(0));
        assertEquals("重大合同清单.xlsx", d.path("fileName").asText(), "file_change 原文：" + changes.get(0));
        assertEquals(42L, d.path("fileId").asLong(), "file_change 要带 fileId：" + changes.get(0));
        assertEquals("MODIFIED", d.path("changeType").asText());
    }

    @Test
    @DisplayName("活跃文档只有 id 没有名字（中途 doc_open_file 换了文档）：按 id 查回真名")
    void activeDocumentNameResolvedById() throws Exception {
        com.checkba.model.entity.ProjectFile f = new com.checkba.model.entity.ProjectFile();
        f.setId(42L);
        f.setName("重大合同清单.xlsx");
        when(projectFileService.getFile(42L)).thenReturn(f);
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已写入 1 个单元格。", tool("fakeSheetWriteCells", "sheet_write_cells"), true));

        run("conv-852-b", "42", null,
                call("sheet_write_cells", WRITE_ARGS), AiMessage.from("<final>已新增一行。</final>"));

        com.fasterxml.jackson.databind.JsonNode d = json(eventDataAll("file_change").get(0));
        assertEquals("重大合同清单.xlsx", d.path("fileName").asText());
        assertEquals(42L, d.path("fileId").asLong());
    }

    @Test
    @DisplayName("没有活跃文档：仍发 MODIFIED（检查点/改动卡片依赖它），但不再出现 Current Document 字面量")
    void noActiveDocumentStillNotifiesWithoutLiteral() throws Exception {
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已写入 1 个单元格。", tool("fakeSheetWriteCells", "sheet_write_cells"), true));

        run("conv-852-c", null, null,
                call("sheet_write_cells", WRITE_ARGS), AiMessage.from("<final>已新增一行。</final>"));

        List<String> changes = eventDataAll("file_change");
        assertEquals(1, changes.size(), "没有活跃文档也要发 MODIFIED：" + sseEvents);
        com.fasterxml.jackson.databind.JsonNode d = json(changes.get(0));
        assertEquals("MODIFIED", d.path("changeType").asText());
        assertEquals("当前文档", d.path("fileName").asText(), "file_change 原文：" + changes.get(0));
        assertTrue(d.path("fileId").isNull() || d.path("fileId").isMissingNode(),
                "不知道是哪份文件就不能编一个 id：" + changes.get(0));
        assertFalse(changes.get(0).contains("Current Document"), changes.get(0));
    }

    @Test
    @DisplayName("新建文件的工具不能被记在活跃文档头上")
    void newDocumentToolIsNotAttributedToActiveDocument() throws Exception {
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已创建 费用明细表.xlsx。", tool("fakeSheetCreateFile", "sheet_create_file"), true));

        run("conv-852-d", "42", "重大合同清单.xlsx",
                call("sheet_create_file", "{\"fileName\":\"费用明细表.xlsx\",\"projectId\":1}"),
                AiMessage.from("<final>已新建。</final>"));

        List<String> changes = eventDataAll("file_change");
        assertEquals(1, changes.size(), sseEvents.toString());
        com.fasterxml.jackson.databind.JsonNode d = json(changes.get(0));
        assertNotEquals("重大合同清单.xlsx", d.path("fileName").asText(), changes.get(0));
        assertFalse(d.path("fileId").asLong() == 42L, changes.get(0));
        assertFalse(changes.get(0).contains("Current Document"), changes.get(0));
    }

    @Test
    @DisplayName("非活跃文档工具带数字 fileId 参数（pdf_* / text_* 一族）：按 id 查回真名，file_change 带该 id")
    void fileIdArgumentResolvedToRealName() throws Exception {
        com.checkba.model.entity.ProjectFile f = new com.checkba.model.entity.ProjectFile();
        f.setId(12L);
        f.setName("尽调底稿-营业执照.pdf");
        when(projectFileService.getFile(12L)).thenReturn(f);
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已高亮 2 处。", tool("fakePdfHighlight", "pdf_highlight"), true));

        // 活跃文档是另一份：不能把 PDF 的改动记在它头上
        run("conv-852-e", "42", "重大合同清单.xlsx",
                call("pdf_highlight", "{\"fileId\":12,\"text\":\"统一社会信用代码\"}"),
                AiMessage.from("<final>已高亮。</final>"));

        List<String> changes = eventDataAll("file_change");
        assertEquals(1, changes.size(), sseEvents.toString());
        com.fasterxml.jackson.databind.JsonNode d = json(changes.get(0));
        assertEquals("尽调底稿-营业执照.pdf", d.path("fileName").asText(), "file_change 原文：" + changes.get(0));
        assertEquals(12L, d.path("fileId").asLong(), "file_change 原文：" + changes.get(0));
    }

    @Test
    @DisplayName("fileId 参数指向查不到的文件：回落「当前文档」，fileId 为 null（不许编一个 id）")
    void unknownFileIdFallsBackWithoutId() throws Exception {
        when(projectFileService.getFile(404L)).thenThrow(new IllegalArgumentException("文件不存在: 404"));
        when(toolRegistry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult(
                "已高亮 1 处。", tool("fakePdfHighlight", "pdf_highlight"), true));

        run("conv-852-f", "42", "重大合同清单.xlsx",
                call("pdf_highlight", "{\"fileId\":\"404\",\"text\":\"甲方\"}"),
                AiMessage.from("<final>已高亮。</final>"));

        List<String> changes = eventDataAll("file_change");
        assertEquals(1, changes.size(), sseEvents.toString());
        com.fasterxml.jackson.databind.JsonNode d = json(changes.get(0));
        assertEquals("当前文档", d.path("fileName").asText(), "file_change 原文：" + changes.get(0));
        assertTrue(d.path("fileId").isNull() || d.path("fileId").isMissingNode(), changes.get(0));
    }
}
