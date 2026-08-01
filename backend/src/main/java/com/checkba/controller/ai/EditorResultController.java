package com.checkba.controller.ai;

import com.checkba.service.ai.EditorBridgeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 编辑器操作结果接收控制器
 * 
 * 接收前端执行编辑器操作后的结果回调（双轨迁移：新路由 /editor-result，
 * 旧路由 /wps-result 保留别名供旧前端使用，一个发布周期后摘除）
 */
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
@Slf4j
public class EditorResultController {

    private final EditorBridgeService editorBridgeService;
    private final com.checkba.service.ProjectAiMessageService messageService;

    /**
     * 接收编辑器操作结果
     * 
     * 前端执行完编辑器操作后，调用此接口返回结果
     */
    @PostMapping({"/editor-result", "/wps-result"})
    public EditorResultResponse receiveEditorResult(@RequestBody EditorResultPayload payload,
                                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 归属校验：结果会以工具输出的身份进入模型上下文并驱动后续改文档动作，
        // 此前既不认人也不认会话，拿到 requestId 就能往别人的 Agent 循环里塞伪造内容
        Long userId = com.checkba.controller.AuthController.getUserIdFromSession(sessionId);
        if (userId == null || !messageService.isConversationOwnedBy(payload.getConversationId(), userId)) {
            log.warn("Rejected editor result: requestId={}, conversationId={}", payload.getRequestId(), payload.getConversationId());
            return new EditorResultResponse(false, "无权回传该会话的结果");
        }

        log.info("Received editor result: requestId={}, success={}", payload.getRequestId(), payload.isSuccess());
        
        try {
            editorBridgeService.completeEditorAction(
                    payload.getRequestId(),
                    payload.isSuccess(),
                    payload.getData(),
                    payload.getError()
            );
            
            return new EditorResultResponse(true, "Result received");
            
        } catch (Exception e) {
            log.error("Failed to process editor result", e);
            return new EditorResultResponse(false, e.getMessage());
        }
    }

    /**
     * 编辑器结果请求体
     */
    @Data
    public static class EditorResultPayload {
        /**
         * 请求 ID（与 SSE 发送的 requestId 对应）
         */
        private String requestId;
        
        /**
         * 会话 ID
         */
        private String conversationId;
        
        /**
         * 是否成功
         */
        private boolean success;
        
        /**
         * 结果数据（成功时）
         */
        private Object data;
        
        /**
         * 错误信息（失败时）
         */
        private String error;
    }

    /**
     * 编辑器结果响应
     */
    @Data
    public static class EditorResultResponse {
        private final boolean received;
        private final String message;
    }
}

