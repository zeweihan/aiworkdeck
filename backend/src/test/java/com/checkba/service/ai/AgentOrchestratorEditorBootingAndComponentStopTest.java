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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dev-board#1016 / #1017 在编排器这一层的两条契约：
 * <ul>
 *   <li>编辑器仍在启动（EDITOR_BOOTING）不计入连续失败：三次也不触发「已连续 N 次失败」的收敛提示，
 *       打转干预也换成不诱导换路的专用提醒——那句提示曾被读成「改用新建文件」，一轮造出四份同名文档；</li>
 *   <li>工具发出 component_required 后本轮以「等待组件」收尾，不再把控制权交回模型。</li>
 * </ul>
 */
class AgentOrchestratorEditorBootingAndComponentStopTest {

    private static final String MODEL = "qwen/qwen3.7-flash";
    private static final String BOOTING =
            "{\"error\": \"编辑器仍在启动\", \"code\": \"EDITOR_BOOTING\", \"retryable\": true}";

    private ChatModelFactory chatModelFactory;
    private ToolRegistry toolRegistry;
    private EditorBridgeService bridge;
    private AgentRunStateService runState;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> seen = new CopyOnWriteArrayList<>();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            seen.add(new ArrayList<>(messages));
            AiMessage msg = script.get(Math.min(calls.getAndIncrement(), script.size() - 1));
            if (msg.text() != null && !msg.text().isEmpty()) handler.onNext(msg.text());
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
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.countByConversationId(any())).thenReturn(2L);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("把这份 PDF 精简一下"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        bridge = mock(EditorBridgeService.class);
        AiContextProperties contextProperties = new AiContextProperties();
        runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), bridge,
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private ScriptModel run(String conversationId, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("把这份 PDF 精简一下");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    private static AiMessage call(String id, String name, String args) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name(name).arguments(args).build()));
    }

    private static List<String> toolResults(ScriptModel model) {
        List<ChatMessage> last = model.seen.get(model.seen.size() - 1);
        List<String> out = new ArrayList<>();
        for (ChatMessage m : last) {
            if (m instanceof ToolExecutionResultMessage t) out.add(t.text());
        }
        return out;
    }

    private static List<String> userTexts(ScriptModel model) {
        List<ChatMessage> last = model.seen.get(model.seen.size() - 1);
        List<String> out = new ArrayList<>();
        for (ChatMessage m : last) {
            if (m instanceof UserMessage u) out.add(u.singleText());
        }
        return out;
    }

    private String bubbleEnd() {
        for (int i = sseEvents.size() - 1; i >= 0; i--) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    @Test
    @DisplayName("EDITOR_BOOTING 连续三次：不触发「已连续 N 次失败」提示，打转提醒不诱导换路")
    void editorBootingThreeTimesNeverTriggersTheFailureNudge() {
        when(toolRegistry.execute(eq("doc_get_document_text"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult(BOOTING, null, true));

        ScriptModel model = run("conv-boot",
                call("t1", "doc_get_document_text", "{}"),
                call("t2", "doc_get_document_text", "{}"),
                call("t3", "doc_get_document_text", "{}"),
                AiMessage.from("<final>编辑器一直没加载好，请确认文档已打开。</final>"));

        assertEquals(4, model.calls.get(), "启动中的三次重试之后模型照常收尾");
        List<String> results = toolResults(model);
        assertEquals(3, results.size());
        for (String r : results) {
            assertFalse(r.contains("已连续"), "启动中不算失败，不该追加收敛提示：" + r);
            assertTrue(r.contains("EDITOR_BOOTING"), r);
        }
        String nudges = String.join("\n", userTexts(model));
        assertFalse(nudges.contains("改用其他工具"), "打转提醒不能诱导换路：" + nudges);
        assertTrue(nudges.contains("编辑器已连续多次仍在启动"), "应换成启动中专用提醒：" + nudges);
        assertTrue(nudges.contains("不要改用新建文件"), nudges);
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-boot").status());
    }

    @Test
    @DisplayName("对照：普通失败连续三次照常触发收敛提示，且措辞不再说「停止当前思路」")
    void ordinaryFailuresStillNudgeWithoutSuggestingANewRoute() {
        when(toolRegistry.execute(eq("doc_find_replace"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("Error: 没找到要替换的文字", null, true));

        ScriptModel model = run("conv-fail",
                call("t1", "doc_find_replace", "{\"find\":\"a\"}"),
                call("t2", "doc_find_replace", "{\"find\":\"b\"}"),
                call("t3", "doc_find_replace", "{\"find\":\"c\"}"),
                AiMessage.from("<final>没找到。</final>"));

        List<String> results = toolResults(model);
        assertEquals(3, results.size());
        assertFalse(results.get(1).contains("已连续"));
        String third = results.get(2);
        assertTrue(third.contains("已连续 3 次工具执行失败"), third);
        assertFalse(third.contains("停止当前思路"), "这句曾被读成「换条路」：" + third);
        assertTrue(third.contains("不要为了绕开失败改用新建文件"), third);
        assertEquals(4, model.calls.get());
    }

    @Test
    @DisplayName("工具发出 component_required：本轮到此收尾（AWAITING_INPUT + reason=component_required），不再调模型")
    void componentRequiredEndsTheTurn() {
        when(toolRegistry.execute(eq("pdf_to_word"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("版式级转换需要本机组件……本轮到此为止。", null, true));
        when(bridge.consumeComponentWait("conv-comp")).thenReturn(true);

        ScriptModel model = run("conv-comp",
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("t1").name("pdf_to_word").arguments("{\"fileId\":7}").build(),
                        ToolExecutionRequest.builder().id("t2").name("write_docx").arguments("{}").build())),
                AiMessage.from("<final>不应走到这里</final>"));

        assertEquals(1, model.calls.get(), "等组件时不该再把控制权交回模型");
        String end = bubbleEnd();
        assertNotNull(end, sseEvents.toString());
        assertTrue(end.contains("\"status\":\"awaiting_input\""), end);
        assertTrue(end.contains("\"reason\":\"component_required\""), end);
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-comp").status());
        org.mockito.Mockito.verify(toolRegistry, org.mockito.Mockito.never())
                .execute(eq("write_docx"), any(), any());
        assertTrue(sseData.stream().anyMatch(d -> d.contains("装好后会自动继续")), sseData.toString());
    }
}
