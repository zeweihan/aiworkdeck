package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 编辑器桥接服务（后端 ↔ 前端嵌入式 LibreOffice 编辑器）
 *
 * 负责：
 * 1. 发送编辑器操作指令到前端（通过 SSE client_action）
 * 2. 管理请求 ID 与 CompletableFuture 的映射
 * 3. 接收前端执行结果并解锁等待的工具调用
 *
 * 工作流程：
 * 1. Agent 调用文档编辑工具 -> DocumentEditTools 调用 executeEditorCommand
 * 2. EditorBridgeService 生成 requestId，发送 SSE 事件，创建 CompletableFuture
 * 3. 前端执行操作后调用 /api/ai/agent/editor-result 返回结果（旧路由 /wps-result 保留别名）
 * 4. EditorResultController 调用 completeEditorAction 解锁 CompletableFuture
 * 5. executeEditorCommand 获取结果并返回给 DocumentEditTools
 *
 * 历史沿革：原名 WpsActionService（WPS WebOffice 时代）。SSE 事件与路由中的
 * wps_* 字符串是前后端契约（见 docs/ai_agent_dev.md §2.2），当前处于双轨迁移期：
 * 每条指令按"新名在前、旧名在后"各发一份（doc_* 与 editor_command + wps_*），前端凭
 * "先见新名"判定新后端并丢弃旧名去重；兼容一个发布周期后摘旧名（AI_ARCHITECTURE.md Phase 3）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EditorBridgeService {

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    // 请求 ID -> CompletableFuture 的映射
    private final ConcurrentHashMap<String, CompletableFuture<EditorActionResult>> pendingRequests = new ConcurrentHashMap<>();
    
    // 当前活跃的 conversationId（由 AgentOrchestrator 设置）
    private final ThreadLocal<String> currentConversationId = new ThreadLocal<>();

    // ConversationID -> IsStreaming Mode
    private final ConcurrentHashMap<String, Boolean> streamingModes = new ConcurrentHashMap<>();
    
    // 编辑器操作超时时间（秒）
    private static final int EDITOR_ACTION_TIMEOUT = 30;

    /**
     * 设置当前会话 ID（由 AgentOrchestrator 在执行工具前调用）
     */
    public void setCurrentConversationId(String conversationId) {
        currentConversationId.set(conversationId);
    }

    /**
     * 获取当前会话 ID
     */
    public String getCurrentConversationId() {
        return currentConversationId.get();
    }

    /**
     * 清除当前会话 ID。
     * 流式回调运行在可复用的线程池线程上，用完必须清理，否则残留的 conversationId
     * 会在该线程被复用于其它会话时被读到，导致编辑器指令发往错误的会话。
     */
    public void clearCurrentConversationId() {
        currentConversationId.remove();
    }

    public void setStreamingMode(String conversationId, boolean enabled) {
        if (enabled) {
            streamingModes.put(conversationId, true);
        } else {
            streamingModes.remove(conversationId);
        }
    }

    public boolean isStreamingMode(String conversationId) {
        return streamingModes.getOrDefault(conversationId, false);
    }

    /**
     * 发送打开文件的 SSE 事件到前端
     * 这是一个单向操作，不需要等待结果
     */
    public void sendOpenFileAction(ProjectFile file) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send open file action");
            return;
        }

        try {
            Map<String, Object> fields = Map.of(
                    "fileId", file.getId(),
                    "fileName", file.getName(),
                    "fileType", file.getFileType(),
                    "wpsFileId", file.getWpsFileId() != null ? file.getWpsFileId() : "",
                    "trackRevisions", true,
                    "userName", "AI WorkDeck"
            );
            sendDualNamedAction("doc_open_file", "wps_open_file", conversationId, fields);
            log.info("Sent doc_open_file action for file: {} (id={})", file.getName(), file.getId());

        } catch (Exception e) {
            log.error("Failed to send open file action", e);
        }
    }

    /**
     * 发送重新加载文件的 SSE 事件到前端
     * 用于在后端修改文件后通知前端编辑器刷新
     */
    public void sendReloadFileAction(ProjectFile file) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send reload file action");
            return;
        }

        try {
            Map<String, Object> fields = Map.of(
                    "fileId", file.getId(),
                    "fileName", file.getName(),
                    "fileType", file.getFileType(),
                    "wpsFileId", file.getWpsFileId() != null ? file.getWpsFileId() : ""
            );
            sendDualNamedAction("doc_reload_file", "wps_reload_file", conversationId, fields);
            log.info("Sent doc_reload_file action for file: {} (id={})", file.getName(), file.getId());

        } catch (Exception e) {
            log.error("Failed to send reload file action", e);
        }
    }

    /**
     * 双轨迁移期的单向 client_action 发送：同一份载荷按"新名在前、旧名在后"各发一次。
     * 顺序是契约的一部分——前端凭"先见新名"判定新后端并丢弃随后的旧名事件去重。
     */
    private void sendDualNamedAction(String newAction, String legacyAction,
                                     String conversationId, Map<String, Object> fields) throws Exception {
        java.util.Map<String, Object> payloadMap = new java.util.HashMap<>(fields);
        payloadMap.put("action", newAction);
        sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
        payloadMap.put("action", legacyAction);
        sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
    }

    /**
     * 发送刷新文件树的 SSE 事件到前端
     */
    public void sendRefreshFilesAction() {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send refresh files action");
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "action", "refresh_files"
            ));
            
            sseEmitterService.send(conversationId, "client_action", payload);
            log.info("Sent refresh_files action");
            
        } catch (Exception e) {
            log.error("Failed to send refresh files action", e);
        }
    }

    /**
     * 发送 PPT 生成配置请求到前端 (UI Interceptor)
     */
    public void sendPptConfigAction(Map<String, Object> configParams) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send ppt config action");
            return;
        }

        try {
            // Append action type
            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>(configParams);
            payloadMap.put("action", "ppt_config_required");
            
            String payload = objectMapper.writeValueAsString(payloadMap);
            
            sseEmitterService.send(conversationId, "client_action", payload);
            log.info("Sent ppt_config_required action");
            
        } catch (Exception e) {
            log.error("Failed to send ppt config action", e);
        }
    }

    /** 埋点：服务端往返（action 是原语枚举名，params 内容不采集） */
    private void recordBridge(String action, String outcome, String conversationId, long startMs) {
        telemetryService.recordConv("editor.bridge", conversationId, Map.of(
                "action", action == null ? "" : action,
                "outcome", outcome,
                "durationMs", System.currentTimeMillis() - startMs));
    }

    /**
     * 执行编辑器命令并等待前端返回结果
     *
     * @param action 操作类型（如 get_selection, find_replace 等）
     * @param params 操作参数
     * @return 执行结果的 JSON 字符串
     */
    public String executeEditorCommand(String action, Map<String, Object> params) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            return "{\"error\": \"No active conversation. Please ensure a document is open.\"}";
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<EditorActionResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        long bridgeStartMs = System.currentTimeMillis();

        try {
            // 构建并发送 SSE 事件（双轨：新名 editor_command 在前、旧名 wps_command 在后，
            // requestId 相同；action 中仅 doc_open_file_sync 有旧名 wps_open_file_sync 需映射）
            String legacyAction = "doc_open_file_sync".equals(action) ? "wps_open_file_sync" : action;
            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
            payloadMap.put("action", action);
            payloadMap.put("params", params != null ? params : Map.of());
            payloadMap.put("requestId", requestId);
            payloadMap.put("conversationId", conversationId);

            payloadMap.put("tool", "editor_command");
            sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
            payloadMap.put("tool", "wps_command");
            payloadMap.put("action", legacyAction);
            sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
            log.info("Sent editor command: action={}, requestId={}", action, requestId);

            // 等待前端执行结果
            EditorActionResult result = future.get(EDITOR_ACTION_TIMEOUT, TimeUnit.SECONDS);
            
            if (result.isSuccess()) {
                recordBridge(action, "ok", conversationId, bridgeStartMs);
                return objectMapper.writeValueAsString(result.getData());
            } else {
                recordBridge(action, "error", conversationId, bridgeStartMs);
                return "{\"error\": \"" + result.getError() + "\"}";
            }

        } catch (TimeoutException e) {
            log.warn("Editor command timed out: action={}, requestId={}", action, requestId);
            recordBridge(action, "timeout", conversationId, bridgeStartMs);
            return "{\"error\": \"操作超时。请确保编辑器已打开并可用。\"}";

        } catch (Exception e) {
            log.error("Failed to execute editor command: action={}", action, e);
            recordBridge(action, "error", conversationId, bridgeStartMs);
            return "{\"error\": \"" + e.getMessage() + "\"}";

        } finally {
            pendingRequests.remove(requestId);
        }
    }

    /**
     * 完成编辑器操作（由 EditorResultController 调用）
     * 
     * @param requestId 请求 ID
     * @param success 是否成功
     * @param data 结果数据
     * @param error 错误信息
     */
    public void completeEditorAction(String requestId, boolean success, Object data, String error) {
        CompletableFuture<EditorActionResult> future = pendingRequests.get(requestId);
        if (future != null) {
            future.complete(new EditorActionResult(success, data, error));
            log.info("Completed editor action: requestId={}, success={}", requestId, success);
        } else {
            log.warn("No pending request found for requestId={}", requestId);
        }
    }

    /**
     * 编辑器操作结果
     */
    public static class EditorActionResult {
        private final boolean success;
        private final Object data;
        private final String error;

        public EditorActionResult(boolean success, Object data, String error) {
            this.success = success;
            this.data = data;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public Object getData() {
            return data;
        }

        public String getError() {
            return error;
        }
    }
}

