package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AgentOrchestrator;
import com.checkba.service.ai.AgentRunStateService;
import com.checkba.service.ai.BackgroundTaskService;
import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.TodoListService;
import com.checkba.service.ai.subagent.SubAgentService;
import com.checkba.service.ai.tools.PptxTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI Agent 控制器：任务级取消两个端点的鉴权/返回形状，以及 PPT 生成结果落库。
 *
 * 控制器是构造器注入，手工装配即可（与 E4IdorAuthTest 同款模式），
 * AuthController.getUserIdFromSession 是静态方法，用 mockStatic 打桩。
 */
@DisplayName("AI Agent 控制器：取消端点与 PPT 结果落库")
class AiAgentControllerTest {

    private ProjectAiMessageService messageService;
    private BackgroundTaskService backgroundTaskService;
    private SubAgentService subAgentService;
    private PptxTools pptxTools;
    private ProjectMemberService projectMemberService;
    private AiAgentController controller;

    @BeforeEach
    void setUp() {
        messageService = mock(ProjectAiMessageService.class);
        backgroundTaskService = mock(BackgroundTaskService.class);
        subAgentService = mock(SubAgentService.class);
        pptxTools = mock(PptxTools.class);
        projectMemberService = mock(ProjectMemberService.class);
        controller = new AiAgentController(
                mock(SseEmitterService.class),
                mock(AgentOrchestrator.class),
                messageService,
                backgroundTaskService,
                pptxTools,
                mock(TodoListService.class),
                mock(AgentRunStateService.class),
                projectMemberService,
                mock(ClientCapabilityService.class),
                subAgentService);
    }

    private AiAgentController.SubtaskCancelRequest subtaskReq(String conv, String subtaskId) {
        AiAgentController.SubtaskCancelRequest r = new AiAgentController.SubtaskCancelRequest();
        r.setConversationId(conv);
        r.setSubtaskId(subtaskId);
        return r;
    }

    private AiAgentController.TaskCancelRequest taskReq(String conv, String taskId) {
        AiAgentController.TaskCancelRequest r = new AiAgentController.TaskCancelRequest();
        r.setConversationId(conv);
        r.setTaskId(taskId);
        return r;
    }

    @Test
    @DisplayName("子任务取消：会话无权 403 且不碰服务；已结束 404；成功 200 且文案只说正在停止")
    void subtaskCancelAuthAndShape() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);

            when(messageService.canUseConversation("conv-1", 7L)).thenReturn(false);
            assertEquals(403, controller.cancelSubtask(subtaskReq("conv-1", "subtask-1"), "s").getStatusCode().value());
            verify(subAgentService, never()).cancel(any(), any());

            when(messageService.canUseConversation("conv-1", 7L)).thenReturn(true);
            when(subAgentService.cancel("subtask-1", "conv-1")).thenReturn(false);
            assertEquals(404, controller.cancelSubtask(subtaskReq("conv-1", "subtask-1"), "s").getStatusCode().value());

            when(subAgentService.cancel("subtask-2", "conv-1")).thenReturn(true);
            ResponseEntity<?> ok = controller.cancelSubtask(subtaskReq("conv-1", "subtask-2"), "s");
            assertEquals(200, ok.getStatusCode().value());
            String body = String.valueOf(ok.getBody());
            assertTrue(body.contains("正在停止"), "只承诺正在停止（打不断在途 LLM 调用）：" + body);
            assertFalse(body.contains("已停止"), "不许谎报已停止：" + body);
        }
    }

    @Test
    @DisplayName("后台任务取消：同款鉴权与返回形状，会话归属校验下推给服务")
    void backgroundTaskCancelAuthAndShape() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);

            when(messageService.canUseConversation("conv-1", 7L)).thenReturn(false);
            assertEquals(403, controller.cancelBackgroundTask(taskReq("conv-1", "t-1"), "s").getStatusCode().value());
            verify(backgroundTaskService, never()).cancelTask(any(), any());

            when(messageService.canUseConversation("conv-1", 7L)).thenReturn(true);
            when(backgroundTaskService.cancelTask("t-1", "conv-1")).thenReturn(false);
            assertEquals(404, controller.cancelBackgroundTask(taskReq("conv-1", "t-1"), "s").getStatusCode().value());

            when(backgroundTaskService.cancelTask("t-2", "conv-1")).thenReturn(true);
            assertEquals(200, controller.cancelBackgroundTask(taskReq("conv-1", "t-2"), "s").getStatusCode().value());
        }
    }

    /**
     * 修既存缺陷：/ppt/generate 的 runAsync 原来把整段成功文本丢掉，
     * 文件生成了但历史里一个字都没有——主 Agent 下一轮不知道这个文件存在。
     * 落库用契约 D：content 给模型（带 fileId 等细节），displayContent 给用户一句人话。
     */
    @Test
    @DisplayName("PPT 生成成功后落一条 ASSISTANT 消息：content 带细节、displayContent 是人话")
    void pptSuccessPersistsAssistantMessage() {
        String toolOutput = "PPTX 生成成功！\n- 文件名: 尽调汇报.pptx\n- 页数: 12\n- 文件 ID: 88\n"
                + "**页面修改**: 可以使用以下工具进行修改：\n- pptx_edit_page: 用自然语言修改页面";
        when(pptxTools.performPptGenerationWithProgress(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean())).thenReturn(toolOutput);

        AiAgentController.PptGenerationRequest req = new AiAgentController.PptGenerationRequest();
        req.setProjectId(42L);
        req.setConversationId("conv-1");
        req.setFileName("尽调汇报.pptx");
        req.setTopic("某公司股权尽调汇报");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(eq(42L), anyLong())).thenReturn(true);

            assertEquals(200, controller.performPptGeneration(req, "s").getStatusCode().value());

            ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> display = ArgumentCaptor.forClass(String.class);
            // 生成跑在 runAsync 上，用 timeout 等它落库
            verify(messageService, timeout(5000)).saveMessage(eq("42"), eq(7L), eq("conv-1"),
                    eq("ASSISTANT"), content.capture(), display.capture());
            assertEquals(toolOutput, content.getValue(), "模型看的那份必须是原样全文（fileId 等细节在里面）");
            assertTrue(display.getValue().contains("尽调汇报.pptx"), "用户看的那份要点名文件：" + display.getValue());
            assertFalse(display.getValue().contains("pptx_edit_page"), "用户气泡里不该出现工具名：" + display.getValue());
        }
    }

    @Test
    @DisplayName("PPT 生成失败也落库：显示文案取首行可读中文，细节仍留给模型")
    void pptFailurePersistsReadableFirstLine() {
        String toolOutput = "PPTX 生成失败: pptx-service 返回 500\n堆栈细节若干";
        when(pptxTools.performPptGenerationWithProgress(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean())).thenReturn(toolOutput);

        AiAgentController.PptGenerationRequest req = new AiAgentController.PptGenerationRequest();
        req.setProjectId(42L);
        req.setConversationId("conv-1");
        req.setTopic("某公司股权尽调汇报");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(eq(42L), anyLong())).thenReturn(true);

            controller.performPptGeneration(req, "s");

            ArgumentCaptor<String> display = ArgumentCaptor.forClass(String.class);
            verify(messageService, timeout(5000)).saveMessage(eq("42"), eq(7L), eq("conv-1"),
                    eq("ASSISTANT"), eq(toolOutput), display.capture());
            assertEquals("PPTX 生成失败: pptx-service 返回 500", display.getValue());
        }
    }
}
