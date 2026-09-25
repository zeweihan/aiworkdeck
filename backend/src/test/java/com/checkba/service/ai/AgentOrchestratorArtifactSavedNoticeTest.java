// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-40（v0.49.0 真机测试批次 P）：Plan 模式把计划落盘到项目根
 * 「AI Assistant Files/&lt;会话文件夹&gt;/」，对话里此前没有任何提示。
 *
 * <p>守三件事：① 提示给完整相对路径（含会话子目录），用户照着能找到；
 * ② 文案不依赖界面位置（同一条流也发到 Office/WPS 任务窗格）；
 * ③ 提示真的作为 text_delta 发进流里，而不只是拼进落库正文。
 */
@DisplayName("Plan 模式落盘后的可见提示")
class AgentOrchestratorArtifactSavedNoticeTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    @AfterEach
    void resetLang() {
        LangText.reset();
    }

    @Test
    @DisplayName("提示给完整相对路径：根目录 / 会话子目录 / 文件名，且不引用界面位置")
    void noticeGivesFullRelativePath() {
        String delta = AgentOrchestrator.artifactSavedNoticeDelta("conv-abc", "三步工作计划.md");
        assertTrue(delta.contains("已保存到项目文件：AI 助手文件/conv-abc/三步工作计划.md"), delta);
        assertFalse(delta.contains("左侧"), "任务窗格里没有左侧资源管理器：" + delta);
        assertFalse(delta.contains("资源管理器"), delta);
    }

    @Test
    @DisplayName("说明随应用语言给中英两版")
    void noticeFollowsAppLanguage() {
        LangText.reset();
        String zh = AgentOrchestrator.artifactSavedNoticeDelta("conv-abc", "Plan.md");

        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
        String enText = AgentOrchestrator.artifactSavedNoticeDelta("conv-abc", "Plan.md");
        assertTrue(enText.contains("Saved to project file: AI Assistant Files/conv-abc/Plan.md"), enText);
        assertFalse(enText.toLowerCase().contains("on the left"), enText);
        assertNotEquals(zh, enText);
    }

    /** 一轮只吐一段固定内容的模型。 */
    private static final class OneShotModel implements StreamingChatLanguageModel {
        private final String text;

        OneShotModel(String text) {
            this.text = text;
        }

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

    @Test
    @DisplayName("落盘成功后提示真的作为 text_delta 发进流里（带实际会话文件夹名），也拼进落库正文")
    void noticeIsStreamedAfterSuccessfulSave() {
        List<String> sseEvents = new CopyOnWriteArrayList<>();
        List<String> sseData = new CopyOnWriteArrayList<>();
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
                        SystemMessage.from("system"), UserMessage.from("给我列个工作计划"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        // 会话文件夹已被用户改名成「股权转让」：提示应给实际名字，不是 conversationId
        ProjectFileService projectFileService = mock(ProjectFileService.class);
        ProjectFile saved = new ProjectFile();
        saved.setId(501L);
        saved.setParentId(99L);
        saved.setName("三步工作计划.md");
        ProjectFile convFolder = new ProjectFile();
        convFolder.setId(99L);
        convFolder.setName("股权转让");
        when(projectFileService.saveArtifactFile(eq(1L), eq("conv-plan"), any(), any(), any())).thenReturn(saved);
        when(projectFileService.findFile(99L)).thenReturn(Optional.of(convFolder));

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new OneShotModel(
                "<artifact type=\"implementation_plan\" name=\"三步工作计划\">\n1. 读合同\n2. 列风险\n3. 出意见\n</artifact>"));

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

        String expected = "AI 助手文件/股权转让/三步工作计划.md";
        boolean streamed = false;
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("text_delta".equals(sseEvents.get(i)) && sseData.get(i).contains(expected)) {
                streamed = true;
                break;
            }
        }
        assertTrue(streamed, "落盘提示必须作为 text_delta 发进流里：" + sseEvents + " / " + sseData);
        verify(messageService).upsertAssistantMessage(any(), any(), any(), any(), contains(expected));
    }
}
