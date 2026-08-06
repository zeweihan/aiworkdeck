package com.checkba.controller.ai;

import com.checkba.service.ai.OfficeBridgeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Office 插件操作结果接收控制器（Phase C 工具桥）。
 *
 * Word 任务窗格插件执行完 office_command 后回传结果。
 * 归属校验思路与 EditorResultController 相同，但会话 ID 不信任请求体——
 * 以后端挂起表中 requestId 登记的 conversationId 为准（插件伪造不了归属）。
 */
@RestController
@RequestMapping("/api/agent/office")
@RequiredArgsConstructor
@Slf4j
public class OfficeResultController {

    private final OfficeBridgeService officeBridgeService;
    private final com.checkba.service.ProjectAiMessageService messageService;

    @PostMapping("/result")
    public OfficeResultResponse receiveOfficeResult(@RequestBody OfficeResultPayload payload,
                                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 归属校验：结果会以工具输出身份进入模型上下文并驱动后续动作，
        // 拿到 requestId 不等于有权回传——必须是该会话的可用用户
        Long userId = com.checkba.controller.AuthController.getUserIdFromSession(sessionId);
        String conversationId = officeBridgeService.getPendingConversationId(payload.getRequestId());
        if (conversationId == null) {
            log.warn("Rejected office result: unknown or expired requestId={}", payload.getRequestId());
            return new OfficeResultResponse(false, "请求不存在或已超时");
        }
        if (!messageService.canUseConversation(conversationId, userId)) {
            log.warn("Rejected office result: requestId={}, conversationId={}", payload.getRequestId(), conversationId);
            return new OfficeResultResponse(false, "无权回传该会话的结果");
        }

        log.info("Received office result: requestId={}, ok={}", payload.getRequestId(), payload.isOk());
        try {
            officeBridgeService.completeOfficeAction(
                    payload.getRequestId(), payload.isOk(), payload.getData(), payload.getError());
            return new OfficeResultResponse(true, "Result received");
        } catch (Exception e) {
            log.error("Failed to process office result", e);
            return new OfficeResultResponse(false, e.getMessage());
        }
    }

    /** 回传请求体：{requestId, ok, data|error} */
    @Data
    public static class OfficeResultPayload {
        /** 请求 ID（与 SSE 下发的 requestId 对应） */
        private String requestId;
        /** 是否成功 */
        private boolean ok;
        /** 结果数据（成功时） */
        private Object data;
        /** 错误信息（失败时） */
        private String error;
    }

    @Data
    public static class OfficeResultResponse {
        private final boolean received;
        private final String message;
    }
}
