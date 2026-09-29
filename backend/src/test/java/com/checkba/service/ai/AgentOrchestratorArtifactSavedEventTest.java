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
import com.checkba.service.review.FileReviewService;
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

    /**
     * 一轮只吐一段固定内容的模型：按 chunks 逐段 onNext，最后以全文 onComplete；
     * chunks 为空时不经 onNext，流式层看不到 artifact。
     */
    private static final class OneShotModel implements StreamingChatLanguageModel {
        private final List<String> chunks;

        OneShotModel(List<String> chunks) {
            this.chunks = chunks;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            for (String c : chunks) handler.onNext(c);
            String text = chunks.isEmpty() ? FULL_TEXT.get() : String.join("", chunks);
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

    private static final ThreadLocal<String> FULL_TEXT = new ThreadLocal<>();

    /** 每次 run 里 saveArtifactFile 收到的 [文件名, 正文]。 */
    private static final List<String[]> SAVED_FILES = new CopyOnWriteArrayList<>();

    private static List<Sent> run(String modelOutput, boolean streamTokens) {
        return runChunks(modelOutput, streamTokens ? List.of(modelOutput) : List.of());
    }

    private static List<Sent> runChunks(String modelOutput, List<String> chunks) {
        return runChunks(modelOutput, chunks, null);
    }

    private static List<Sent> runChunks(String modelOutput, List<String> chunks,
                                        java.util.function.Function<List<Sent>, FileReviewService> reviewFactory) {
        FULL_TEXT.set(modelOutput);
        SAVED_FILES.clear();
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
                    SAVED_FILES.add(new String[]{inv.getArgument(2), inv.getArgument(3)});
                    saved.setName(inv.getArgument(2));
                    return saved;
                });
        when(projectFileService.findFile(5L)).thenReturn(Optional.of(convFolder));

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new OneShotModel(chunks));

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
        if (reviewFactory != null) orchestrator.setFileReviewService(reviewFactory.apply(sent));

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

    @Test
    @DisplayName("先流 code 再流 implementation_plan：saved 事件的 id 是计划那份的，不是第一份")
    void savedEventPicksIdOfSameType() throws Exception {
        String code = "<artifact type=\"code\">\nprint(1)\n</artifact>";
        String plan = "<artifact type=\"implementation_plan\" name=\"示例计划\">\n# 计划\n</artifact>";
        List<Sent> sent = runChunks(code + plan, List.of(code, plan));
        String planId = null;
        String codeId = null;
        for (Sent s : sent) {
            if (!"artifact".equals(s.event())) continue;
            JsonNode node = JSON.readTree(s.data());
            if (!"create".equals(node.path("operation").asText())) continue;
            if ("implementation_plan".equals(node.path("type").asText())) planId = node.path("id").asText();
            if ("code".equals(node.path("type").asText())) codeId = node.path("id").asText();
        }
        assertNotNull(codeId, "流式层应为 code 发 create：" + sent);
        assertNotNull(planId, "流式层应为计划发 create：" + sent);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved, "落盘成功后必须发 saved 事件：" + sent);
        assertEquals(planId, saved.path("id").asText());
    }

    @Test
    @DisplayName("计划落盘后立即把该文件未完成的审阅记录作废（supersedeOpen），且先于 saved 事件")
    void savedSupersedesOpenReview() throws Exception {
        String plan = "<artifact type=\"implementation_plan\" name=\"示例计划\">\n# 计划 v2\n</artifact>";
        List<Sent> sent = runChunks(plan, List.of(plan), log -> {
            FileReviewService review = mock(FileReviewService.class);
            doAnswer(inv -> {
                log.add(new Sent("supersedeOpen", String.valueOf((Object) inv.getArgument(0))));
                return null;
            }).when(review).supersedeOpen(any());
            return review;
        });
        int supIdx = indexOf(sent, s -> "supersedeOpen".equals(s.event()));
        assertTrue(supIdx >= 0, "落盘后必须调 supersedeOpen：" + sent);
        assertEquals("77", sent.get(supIdx).data());
        int savedIdx = indexOf(sent, s -> "artifact".equals(s.event()) && s.data().contains("\"saved\""));
        assertTrue(savedIdx > supIdx, "supersedeOpen 必须先于 saved 事件：" + sent);
    }

    @Test
    @DisplayName("#1052 先流带名字的 code 再流计划：落盘的是计划的名字与正文，saved id 是计划那份的")
    void codeBeforePlanSavesThePlanNotTheCode() throws Exception {
        String code = "<artifact type=\"code\" name=\"脚本\">print(1)</artifact>";
        String plan = "<artifact type=\"implementation_plan\" name=\"示例计划\"># 计划</artifact>";
        List<Sent> sent = runChunks(code + plan, List.of(code, plan));
        assertEquals(1, SAVED_FILES.size(), "只落盘一份：" + SAVED_FILES);
        assertEquals("示例计划.md", SAVED_FILES.get(0)[0]);
        assertEquals("# 计划", SAVED_FILES.get(0)[1]);
        String planId = null;
        for (Sent s : sent) {
            if (!"artifact".equals(s.event())) continue;
            JsonNode node = JSON.readTree(s.data());
            if ("create".equals(node.path("operation").asText())
                    && "implementation_plan".equals(node.path("type").asText())) planId = node.path("id").asText();
        }
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved, "落盘成功后必须发 saved 事件：" + sent);
        assertEquals(planId, saved.path("id").asText());
        assertEquals("AI 助手文件/conv-plan/示例计划.md", saved.path("filePath").asText());
        assertEquals("implementation_plan", saved.path("type").asText());
        assertTrue(sent.stream().anyMatch(s -> "bubble_end".equals(s.event())
                && s.data().contains("awaiting_approval")), "计划仍要停机等审批：" + sent);
    }

    @Test
    @DisplayName("#1052 先计划后 code：也只存计划")
    void planBeforeCodeSavesOnlyThePlan() throws Exception {
        String plan = "<artifact name=\"示例计划\" type=\"implementation_plan\"># 计划</artifact>";
        String code = "<artifact type=\"code\" name=\"脚本\">print(1)</artifact>";
        runChunks(plan + code, List.of(plan, code));
        assertEquals(1, SAVED_FILES.size(), "只落盘一份：" + SAVED_FILES);
        assertEquals("示例计划.md", SAVED_FILES.get(0)[0]);
        assertEquals("# 计划", SAVED_FILES.get(0)[1]);
    }

    @Test
    @DisplayName("#1052 只有带名字的 task_list：照旧按名字落盘，不停机")
    void namedTaskListStillSaved() throws Exception {
        List<Sent> sent = run("<artifact type=\"task_list\" name=\"清单\">- 事项</artifact>", true);
        assertEquals(1, SAVED_FILES.size(), "只落盘一份：" + SAVED_FILES);
        assertEquals("清单.md", SAVED_FILES.get(0)[0]);
        assertEquals("- 事项", SAVED_FILES.get(0)[1]);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved);
        assertEquals("task_list", saved.path("type").asText());
        assertFalse(sent.stream().anyMatch(s -> "bubble_end".equals(s.event())
                && s.data().contains("awaiting_approval")), "task_list 不停机：" + sent);
    }

    @Test
    @DisplayName("#1052 只有 code artifact：不落盘")
    void codeOnlyIsNotSaved() {
        run("<artifact type=\"code\" name=\"脚本\">print(1)</artifact>", true);
        assertEquals(0, SAVED_FILES.size(), "非计划类不落盘：" + SAVED_FILES);
    }

    @Test
    @DisplayName("#1052 task_list 在前、implementation_plan 在后：落盘的是计划，停机等审批")
    void implementationPlanWinsOverEarlierTaskList() throws Exception {
        String tasks = "<artifact type=\"task_list\" name=\"清单\">- 事项</artifact>";
        String plan = "<artifact type=\"implementation_plan\" name=\"示例计划\"># 计划</artifact>";
        List<Sent> sent = runChunks(tasks + plan, List.of(tasks, plan));
        assertEquals(1, SAVED_FILES.size(), "只落盘一份：" + SAVED_FILES);
        assertEquals("示例计划.md", SAVED_FILES.get(0)[0]);
        assertEquals("# 计划", SAVED_FILES.get(0)[1]);
        JsonNode saved = artifactEvent(sent, "saved");
        assertNotNull(saved);
        assertEquals("implementation_plan", saved.path("type").asText());
        assertTrue(sent.stream().anyMatch(s -> "bubble_end".equals(s.event())
                && s.data().contains("awaiting_approval")), "计划仍要停机等审批：" + sent);
    }
}
