package com.checkba.service.ai;

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
 * Office 插件桥接服务（后端 ↔ Word 任务窗格插件）。
 *
 * 与 {@link EditorBridgeService}（LOWA 编辑器桥）逐字同构但完全独立：
 * 不共享超时常量、不参与 doc_* 与 wps_* 双轨命名、契约从第一天起就是单名。
 *
 * 工作流程：
 * 1. Agent 调用 office_* 工具 -> OfficeEditTools 调用 executeOfficeCommand
 * 2. 生成 requestId，经 SSE client_action 下发（tool 固定为 "office_command"，
 *    payload {requestId, command, args, conversationId}），创建 CompletableFuture 等待
 * 3. 插件执行 Office.js 操作后 POST /api/agent/office/result 回传
 *    （body {requestId, ok, data|error}，控制器做会话归属校验）
 * 4. completeOfficeAction 解锁 CompletableFuture，工具拿到结果返回给模型
 *
 * 失败一律以 {"error": "..."} JSON 返回——ToolRegistry.ToolResult.success()
 * 靠这个前缀识别失败，防止绿勾空转（F-09 教训）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfficeBridgeService {

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    /** 挂起的请求：requestId -> (conversationId, future)。conversationId 供回传端点做归属校验。 */
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /** Office 插件操作超时（秒）。独立常量，不与 LOWA 的 EDITOR_ACTION_TIMEOUT 共享。 */
    private static final int OFFICE_ACTION_TIMEOUT_SECONDS = 30;

    /** 实际生效的超时秒数（测试用包内可见 setter 覆盖，生产恒为常量值）。 */
    private volatile int timeoutSeconds = OFFICE_ACTION_TIMEOUT_SECONDS;

    void setTimeoutSecondsForTest(int seconds) {
        this.timeoutSeconds = seconds;
    }

    private record PendingRequest(String conversationId, CompletableFuture<OfficeActionResult> future) {
    }

    /**
     * 下发一条 Office 命令并阻塞等待插件回传结果。
     *
     * @param conversationId 会话 ID（office_* 工具经 ToolRegistry 服务端注入）
     * @param command        命令名（get_text / get_selection / search / replace_text / insert_text / add_comment）
     * @param args           命令参数
     * @return 成功时为结果数据的 JSON；失败时为 {"error": "..."} JSON
     */
    public String executeOfficeCommand(String conversationId, String command, Map<String, Object> args) {
        if (conversationId == null || conversationId.isBlank()) {
            return errorJson("缺少会话上下文，无法下发 Office 命令。");
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<OfficeActionResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingRequest(conversationId, future));

        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("tool", "office_command");
            payload.put("requestId", requestId);
            payload.put("command", command);
            payload.put("args", args != null ? args : Map.of());
            payload.put("conversationId", conversationId);
            sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payload));
            log.info("Sent office command: command={}, requestId={}", command, requestId);

            OfficeActionResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (result.ok()) {
                return objectMapper.writeValueAsString(result.data());
            }
            String error = result.error() != null && !result.error().isBlank()
                    ? result.error() : "插件执行失败（未提供错误信息）";
            return errorJson(error);

        } catch (TimeoutException e) {
            log.warn("Office command timed out: command={}, requestId={}", command, requestId);
            return errorJson("操作超时：Office 插件未在 " + timeoutSeconds
                    + " 秒内返回结果。请确认 Word 中的插件任务窗格处于打开状态。");
        } catch (Exception e) {
            log.error("Failed to execute office command: command={}", command, e);
            return errorJson("Office 命令执行异常：" + e.getMessage());
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    /**
     * 挂起请求所属的会话 ID；无此请求（未知 requestId 或已超时清理）返回 null。
     * 供回传端点先校验会话归属、再解锁 future。
     */
    public String getPendingConversationId(String requestId) {
        if (requestId == null) {
            return null;
        }
        PendingRequest pending = pendingRequests.get(requestId);
        return pending != null ? pending.conversationId() : null;
    }

    /**
     * 完成一次 Office 操作（由 OfficeResultController 在归属校验通过后调用）。
     */
    public void completeOfficeAction(String requestId, boolean ok, Object data, String error) {
        PendingRequest pending = pendingRequests.get(requestId);
        if (pending == null) {
            log.warn("No pending office request found for requestId={}", requestId);
            return;
        }
        pending.future().complete(new OfficeActionResult(ok, data, error));
        log.info("Completed office action: requestId={}, ok={}", requestId, ok);
    }

    /** 统一的失败 JSON（ToolResult.success() 依赖 {"error" 前缀识别失败）。 */
    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message == null ? "未知错误" : message));
        } catch (Exception e) {
            // message 已经过 Jackson 失败才会走到这里，保底手拼一个无特殊字符的错误
            return "{\"error\": \"Office 桥接序列化失败\"}";
        }
    }

    /** 一次 Office 操作的结果 */
    public record OfficeActionResult(boolean ok, Object data, String error) {
    }
}
