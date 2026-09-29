// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.LangText;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * 计划审阅（dev-board#1022）：计划落盘后发一条 {@code artifact saved} 事件，
 * 把 fileId 与相对路径交给前端，让计划卡能打开那份 .md 做修订。
 */
@DisplayName("计划落盘后的 artifact saved 事件")
class AgentOrchestratorArtifactSavedEventTest {

    private static final String MODEL = "qwen/qwen3.7-flash";
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void resetLang() {
        LangText.reset();
    }

    /** 一轮只吐一段固定内容的模型；streamTokens=false 时不经 onNext，流式层看不到 artifact。 */
    private static final class OneShotModel implements StreamingChatLanguageModel {
        private final String text;
        private final boolean streamTokens;

        OneShotModel(String text, boolean streamTokens) {
            this.text = text;
            this.streamTokens = streamTokens;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            if (streamTokens) handler.onNext(text);
            handler.onComplete(Response.from(AiMessage.from(text)));
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            generate(messages, handler);
        }
    }

    private record Sent(String event, String data) {
    }

    private static List<Sent> run(String modelOutput, boolean streamTokens) {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sent.add(new Sent(inv.getArgument(1), String.valueOf((Object) inv.getArgument(2))));
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
                        SystemMessage.from("system"), UserMessage.from("给我列个工作计划"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        ProjectFileService projectFileService = mock(ProjectFileService.class);
        ProjectFile saved = new ProjectFile();
        saved.setId(77L);
        saved.setParentId(5L);
        ProjectFile convFolder = new ProjectFile();
        convFolder.setId(5L);
        convFolder.setName("conv-plan");
        when(projectFileService.saveArtifactFile(eq(1L), eq("conv-plan"), any(), any(), any()))
                .thenAnswer(inv -> {
                    saved.setName(inv.getArgument(2));
                    return saved;
                });
        when(projectFileService.findFile(5L)).thenReturn(Optional.of(convFolder));

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new OneShotModel(modelOutput, streamTokens));

        AiContextProperties contextProperties = new AiContextProperties();
        AgentRunStateService runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                projectFileService, mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-plan");
        request.setMessage("给我列个工作计划");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return sent;
    }

    private static JsonNode artifactEvent(List<Sent> sent, String operation) throws Exception {
        for (Sent s : sent) {
            if (!"artifact".equals(s.event())) continue;
            JsonNode node = JSON.readTree(s.data());
            if (operation.equals(node.path("operation").asText())) return node;
        }
        return null;
    }

    private static int indexOf(List<Sent> sent, java.util.function.Predicate<Sent> p) {
        for (int i = 0; i < sent.size(); i++) if (p.test(sent.get(i))) return i;
        return -1;
    }

    @Test
    @DisplayName("计划落盘后发一条 artifact saved 事件，带 fileId、相对路径与类型，且排在「已保存」提示之前")
    void savedEventCarriesFileIdAndPath() throws Exception {
        List<Sent> sent = run("<artifact type=\"implementation_plan\" name=\"示例计划\">\n# 计划\n- 第一步\n</artifact>", true);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved, "落盘成功后必须发 saved 事件：" + sent);
        assertEquals(77L, saved.path("fileId").asLong());
        assertEquals("AI 助手文件/conv-plan/示例计划.md", saved.path("filePath").asText());
        assertEquals("implementation_plan", saved.path("type").asText());

        int savedIdx = indexOf(sent, s -> "artifact".equals(s.event()) && s.data().contains("\"saved\""));
        int noticeIdx = indexOf(sent, s -> "text_delta".equals(s.event()) && s.data().contains("已保存到项目文件"));
        assertTrue(noticeIdx > savedIdx, "saved 事件必须先于「已保存到项目文件」提示：" + sent);
    }

    @Test
    @DisplayName("saved 事件的 filePath 与对话里「已保存到项目文件」那行的路径逐字相同")
    void savedPathMatchesNoticeVerbatim() throws Exception {
        List<Sent> sent = run("<artifact type=\"implementation_plan\" name=\"示例计划\">\n# 计划\n</artifact>", true);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved);
        String notice = AgentOrchestrator.artifactSavedNoticeDelta("conv-plan", "示例计划.md");
        assertTrue(notice.endsWith(saved.path("filePath").asText()), notice);
    }

    @Test
    @DisplayName("流式层发过 create 的 artifact id 对齐到 saved 事件")
    void savedEventReusesStreamedArtifactId() throws Exception {
        List<Sent> sent = run("<artifact type=\"implementation_plan\" name=\"示例计划\">\n# 计划\n</artifact>", true);
        JsonNode create = artifactEvent(sent, "create");
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(create, "流式层应发 create：" + sent);
        assertNotNull(saved);
        String streamedId = create.path("id").asText();
        assertFalse(streamedId.isEmpty());
        assertEquals(streamedId, saved.path("id").asText());
    }

    @Test
    @DisplayName("对不上流式 id 时 saved 事件 id 为空串，仍带 fileId + filePath")
    void savedEventWithoutStreamedIdStillCarriesFile() throws Exception {
        List<Sent> sent = run("<artifact type=\"task_list\">\n- 事项\n</artifact>", false);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved, "落盘成功后必须发 saved 事件：" + sent);
        assertEquals("", saved.path("id").asText("x"));
        assertEquals(77L, saved.path("fileId").asLong());
        assertEquals("AI 助手文件/conv-plan/Task List.md", saved.path("filePath").asText());
        assertEquals("task_list", saved.path("type").asText());
    }
}
