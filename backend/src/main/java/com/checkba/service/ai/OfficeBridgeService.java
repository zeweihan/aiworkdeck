// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.addin.PaneRegistry;
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

    /** Office 插件操作的默认超时（秒）。独立常量，不与 LOWA 的 EDITOR_ACTION_TIMEOUT 共享。 */
    private static final int OFFICE_ACTION_TIMEOUT_SECONDS = 30;

    /**
     * 跑得久的命令按 command 分级放宽超时（dev-board#419），与
     * {@link EditorBridgeService#ACTION_TIMEOUT_SECONDS} 同一条纪律：
     * <b>平超时是「后端先放弃、模型重发一次造成双改」的成因</b>。
     *
     * <p>凡是「一次调用做 N 件事」的原语都必须进这张表——它们在真机上必然
     * 跑得比单处修改久（批量改写要在一个 Word.run 里定位并落笔几十处，
     * 整篇套用标准格式是逐段落笔）。新增这类原语忘了加，症状不是报错而是
     * 内容被写两遍，最难查。
     *
     * <p><b>漏登记是沉默的，所以这张表有护栏</b>（dev-board#806，审计 B-06）：
     * {@code OfficeBridgeTimeoutCoverageTest} 扫 OfficeEditTools 源码，凡带
     * {@code ...Json} 批量参数的工具不在表里即转红；参数看不出批量、实现却要遍历整篇的，
     * 在那条用例的「判断档」里逐条列了理由。在它之前这张表只有前两条，
     * insert_table / excel_set_values / ppt_add_table 这些明显的批量原语全在 30 秒上吊着。
     *
     * <p><b>读取类刻意不进表</b>：LOWA 桥把读取类抬到 120 秒是 dev-board#729 ③ 拿真机
     * telemetry 换来的（86 次超时里 79 次是读命令），插件桥这边没有同等证据；
     * 而读取超时只是白等一轮，不会造成双写。等有真机数据再说，不跟着抄。
     */
    static final Map<String, Integer> ACTION_TIMEOUT_SECONDS = Map.ofEntries(
            // ===== Word 面 =====
            // 一个 Word.run 里定位并落笔最多 50 处
            Map.entry("replace_batch", 120),
            // 整篇按律所标准格式落笔（按 run 合并后仍是逐段区间）
            Map.entry("apply_standard_format", 120),
            // 一次建整张表并逐格落字；LOWA 桥的同名原语也是 120
            Map.entry("insert_table", 120),
            // 从锚点起给连续 N 段套编号；旧宿主没有 List API 时退化成逐段手写编号前缀
            Map.entry("set_numbering", 120),
            // 整张表逐格设边框/底纹，格数随表大小线性增长
            Map.entry("format_table", 120),
            // 单图上限 2MB，经 base64 过桥后体积再膨胀约三分之一
            Map.entry("insert_image", 120),
            // ===== Excel 面 =====
            // 上限 2000 格一次写入
            Map.entry("excel_set_values", 120),
            // 2000 格公式写入后还要回读一遍、再用 SpecialCells 扫错误格
            Map.entry("excel_set_formulas", 120),
            // 建缓存 → 建透视表 → 逐个映射字段 → 字段对不上还要把刚建的表删掉
            Map.entry("excel_add_pivot_table", 120),
            // ===== PPT 面 =====
            // 追加空白页 + moveTo 挪位置 + 标题/正文两个文本框，一条命令里四次 sync
            Map.entry("ppt_add_slide", 120),
            // 建表并逐格落字
            Map.entry("ppt_add_table", 120),
            // 遍历全篇每页每个形状（含组合与表格递归）找命中，再逐处从右到左替换
            Map.entry("ppt_replace_text", 120),
            // 与 ppt_replace_text 同一条全篇遍历，只是把替换换成设字体
            Map.entry("ppt_format_text", 120));

    static int timeoutSecondsFor(String command) {
        if (command == null) return OFFICE_ACTION_TIMEOUT_SECONDS; // Map.of 对 null 键抛 NPE
        return ACTION_TIMEOUT_SECONDS.getOrDefault(command, OFFICE_ACTION_TIMEOUT_SECONDS);
    }

    /**
     * 超时失败文案的开头。跨文档读取（OpenDocSource）靠它认出超时、换掉下面那句只适用于写入的
     * 「请不要直接重试这条写入命令」，所以改超时文案时开头必须仍是这个常量。
     */
    public static final String TIMEOUT_PREFIX = "操作超时";

    /** 测试覆盖用的超时（>0 时压过分级表；生产恒为 0，走 timeoutSecondsFor）。 */
    private volatile int timeoutOverrideSeconds = 0;

    void setTimeoutSecondsForTest(int seconds) {
        this.timeoutOverrideSeconds = seconds;
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
        return dispatch(conversationId, command, args, null);
    }

    /** 跨窗格下发的发起方（dev-board#717）：B 窗格据此强制标修订、记修订记录。 */
    public record CrossPaneOrigin(String paneId, String docName, String conversationId) {
    }

    /**
     * 把命令下发到同一账号的另一个窗格（dev-board#717）。
     *
     * <p>发往目标窗格<b>当前</b>的会话，载荷多一个 {@code origin} 键。
     * 归属校验仍由 OfficeResultController 按挂起表里登记的目标会话做。
     *
     * <p><b>「窗格还在不在」只看心跳，不看 SSE 有没有 emitter。</b>后端每轮结束都主动关掉 SSE
     * 流（AgentOrchestrator/AgentStreamHandler），窗格要退避 1~30 秒才重连——一个开得好好的
     * 窗格在这段空档里就是「没有 emitter」的。按 emitter 判活会把这段空档诬成「窗格没打开」，
     * 而本窗格的 executeOfficeCommand 从来不这么做：client_action 进补发缓冲，重连时按
     * Last-Event-ID 补回去（dev-board#287），照样执行。跨窗格没有理由比它脆。
     * 真正关掉的窗格由 PaneRegistry 的 90 秒心跳过期兜住，调用方（OpenDocSource.target）
     * 在这之前就报「窗格已经关闭」了。
     *
     * <p>origin 必须有：B 窗格靠它判定「这是跨文档写入」并强制标修订，
     * 缺了它的写入到了 B 那边会被当成本窗格自己的操作，痕迹保证就落空了。
     */
    public String executeOnPane(PaneRegistry.PaneInfo target, String command,
                                Map<String, Object> args, CrossPaneOrigin origin) {
        if (target == null || target.conversationId() == null || target.conversationId().isBlank()) {
            String name = target == null || target.docName() == null || target.docName().isBlank()
                    ? "目标文档" : "《" + target.docName() + "》";
            return errorJson(name + "的 AI WorkDeck 窗格还没有登记会话，请在该文档里打开 AI WorkDeck 窗格后重试。");
        }
        if (origin == null) {
            return errorJson("缺少发起方信息，无法跨文档下发命令。");
        }
        Map<String, Object> o = new java.util.HashMap<>();
        o.put("paneId", origin.paneId());
        o.put("docName", origin.docName());
        o.put("conversationId", origin.conversationId());
        log.info("Cross-pane office command: command={}, targetPane={}, originPane={}",
                command, target.paneId(), origin.paneId());
        return dispatch(target.conversationId(), command, args, Map.of("origin", o));
    }

    /**
     * 下发并阻塞等回传。extra 非空时并入载荷（跨窗格的 origin）。
     */
    private String dispatch(String conversationId, String command, Map<String, Object> args,
                            Map<String, Object> extra) {
        String requestId = UUID.randomUUID().toString();
        int timeoutSeconds = timeoutOverrideSeconds > 0 ? timeoutOverrideSeconds : timeoutSecondsFor(command);
        CompletableFuture<OfficeActionResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingRequest(conversationId, future));

        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("tool", "office_command");
            payload.put("requestId", requestId);
            payload.put("command", command);
            payload.put("args", args != null ? args : Map.of());
            payload.put("conversationId", conversationId);
            if (extra != null) payload.putAll(extra);
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
            // **超时不等于没做**（dev-board#288）：命令已经下发给任务窗格，宿主那边很可能
            // 已经落笔了，只是回执没能在窗口期内回来（断线空档、大文档慢命令都会）。
            // 旧文案只说「超时」，模型的自然反应就是原样重试一次——于是同一段内容
            // 被写进文档两遍，律师拿到的是重复条款。所以这里必须明确禁止直接重试，
            // 并指出先核对。写入类命令尤其要说死。
            return errorJson(TIMEOUT_PREFIX + "：插件未在 " + timeoutSeconds + " 秒内返回结果。"
                    + "注意：命令已经下发，宿主端**可能已经执行成功**，只是回执没回来。"
                    + "请不要直接重试这条写入命令（会写入两遍），"
                    + "先用读取类工具核对文档当前内容，确认没生效再重试。"
                    + "若反复超时，请提示用户确认 Word/WPS 里的任务窗格仍然打开着。");
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
