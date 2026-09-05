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
    // 样式画像解析（dev-board#111）：字段注入、可缺席——构造器不动，手工 new 的测试与 EvalHarness 免改
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StyleProfileResolver styleProfileResolver;

    /** 仅测试用：手工 new 的实例挂上解析器。 */
    void setStyleProfileResolver(StyleProfileResolver resolver) {
        this.styleProfileResolver = resolver;
    }

    /**
     * 画像不是 house-default 时转成 Map（随 doc_open_file / doc_open_file_sync 下发，前端编辑器
     * 就绪后追发 set_style_profile）；是 house-default 或画像为空时返回 null——worker 默认就是
     * house-default，不必白发一条。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nonHouseProfileMap(com.checkba.util.style.StyleProfile profile) {
        if (profile == null) return null;
        com.checkba.util.style.StyleProfile house = com.checkba.util.style.StyleProfiles.houseDefault();
        if (profile.root().equals(house.root())) return null;
        return com.checkba.util.style.StyleProfiles.mapper().convertValue(profile.root(), Map.class);
    }

    /** 该文件所在项目的画像（非 house-default 才返回 Map）；解析器缺席或解析失败一律 null。 */
    private Map<String, Object> styleProfileFor(ProjectFile file) {
        if (styleProfileResolver == null || file == null || file.getProjectId() == null) return null;
        try {
            return nonHouseProfileMap(styleProfileResolver.resolve(file.getProjectId(), null));
        } catch (Exception e) {
            log.warn("项目 {} 画像解析失败，打开文件不带画像: {}", file.getProjectId(), e.getMessage());
            return null;
        }
    }

    /**
     * 请求 ID -> 在等的那一轮。
     *
     * <p>连会话一起记：结果回传的鉴权键必须是「这个 requestId 是给哪个会话发的」，
     * 而不是调用方自己报的 conversationId。此前只存 future，控制器又只校验
     * payload 里客户端自报的会话（是他自己的会话就放行）——两把钥匙不是同一把，
     * 拿到别的会话的 requestId 就能把伪造内容作为工具结果塞进那一轮的 Agent 循环，
     * 而那个结果会被当成可信的文档内容驱动后续改文档动作。
     */
    private final ConcurrentHashMap<String, PendingAction> pendingRequests = new ConcurrentHashMap<>();

    /** 一次在途的编辑器命令：它属于哪个会话，以及谁在等它。 */
    private record PendingAction(String conversationId, CompletableFuture<EditorActionResult> future) {}
    
    // 当前活跃的 conversationId（由 AgentOrchestrator 设置）
    private final ThreadLocal<String> currentConversationId = new ThreadLocal<>();

    // ConversationID -> IsStreaming Mode
    private final ConcurrentHashMap<String, Boolean> streamingModes = new ConcurrentHashMap<>();
    
    // 编辑器操作超时时间（秒）——默认值，交互类命令用
    private static final int EDITOR_ACTION_TIMEOUT = 30;

    /**
     * 按 action 分级的超时（秒，dev-board#108）。整文档装载/导出与全文批量改稿远超 30s
     * （150 页实测 find_replace 150 命中 20s+、apply_house_style 30s+），而 worker 不会因为
     * 后端放弃等待就停下——超时后模型被告知失败可能重发一次，造成双改。
     * 与前端 libreofficeExecutorClient.js / zetaOfficeRelay.js 的 ACTION_BUDGET_MS 三处同表。
     */
    static final Map<String, Integer> ACTION_TIMEOUT_SECONDS = Map.ofEntries(
            Map.entry("doc_open_file_sync", 180),
            Map.entry("find_replace", 120),
            Map.entry("apply_house_style", 120),
            Map.entry("resolve_all_revisions", 120),
            Map.entry("resolve_revisions", 120),
            Map.entry("insert_table", 120),
            Map.entry("apply_style_profile", 120),
            Map.entry("export_document", 180),
            // 整段插入类（dev-board#464）：一份十几页的报告经修订逐行落字远超 30s，
            // worker 那边照旧写完，后端却已经放弃等待。
            Map.entry("insert_at_cursor", 120),
            Map.entry("insert_under_heading", 120),
            Map.entry("replace_selection", 120),
            Map.entry("modify_paragraph", 120));

    /**
     * 超时回执（dev-board#464）。「后端不再等」不等于「没执行」——worker 打不断，
     * 超时那一刻内容很可能已经落进文档。旧文案（"操作超时。请确保编辑器已打开并可用。"）
     * 被模型读成失败，原样重发一次，用户看到同一份长报告以修订插了两遍。
     */
    static final String TIMEOUT_RESULT_JSON = "{\"error\": \"操作超时：编辑器可能仍在执行该命令，"
            + "内容可能已写入。不要重发同一命令，先用读取工具（如 doc_get_document_text）确认文档状态。\"}";

    /**
     * 本轮（run）内已下发过的整段插入：conversationId -> 指纹集合。
     * {@link #clearForNewRun} 在每条新用户消息开始时清空。
     */
    private final ConcurrentHashMap<String, java.util.Set<String>> dispatchedBulkInserts = new ConcurrentHashMap<>();

    /** 会话当前在编辑的文档（去重闸的作用域）：同一段条款插进两份不同合同不算重复。 */
    private final ConcurrentHashMap<String, String> activeDocKeys = new ConcurrentHashMap<>();

    /** 进闸的插入类 action -> 承载正文的参数名。与 ACTION_TIMEOUT_SECONDS 里那四条同一批。 */
    private static final Map<String, String> BULK_INSERT_TEXT_PARAM = Map.of(
            "insert_at_cursor", "text",
            "insert_under_heading", "content",
            "replace_selection", "text",
            "modify_paragraph", "newText");

    /** 短于此长度不进闸：逐条补编号、逐格填表这类重复短插入是正常操作。 */
    static final int BULK_INSERT_MIN_CHARS = 200;

    static int timeoutSecondsFor(String action) {
        if (action == null) return EDITOR_ACTION_TIMEOUT; // Map.of 对 null 键抛 NPE
        return ACTION_TIMEOUT_SECONDS.getOrDefault(action, EDITOR_ACTION_TIMEOUT);
    }

    /** 记住这个会话此刻在编辑哪份文档（去重闸的作用域键）。 */
    void noteActiveDocument(String conversationId, Object fileId) {
        if (conversationId == null || fileId == null) return;
        activeDocKeys.put(conversationId, String.valueOf(fileId));
    }

    /** 新一轮（新用户消息）开始：清空本轮的整段插入登记。 */
    public void clearForNewRun(String conversationId) {
        if (conversationId != null) dispatchedBulkInserts.remove(conversationId);
    }

    /**
     * 本轮重复整段插入的确定性去重闸（dev-board#464）。
     *
     * <p>提示词层面拦不住：超时被 ToolRegistry 归类成失败、编排器还在末位追一句
     * "the operation did NOT take effect"，模型于是原样重发，而 worker 早已把第一份写完——
     * 用户看到同一份长报告以修订插了两遍。这里照 {@code AgentOrchestrator} 拦 doc_open_file
     * 的先例在分发层直接拦下，不再指望模型自觉。
     *
     * <p>命中条件收得很紧，只挡真正的重复：同一会话、同一轮、同一文档、同一 action，
     * 且**整组参数逐字节相同**（换标题、换段落号、换内容都算另一处插入），正文长度还要
     * 达到 {@link #BULK_INSERT_MIN_CHARS}。
     *
     * @return 命中返回给模型的结构化错误；不命中返回 null，并把这次登记下来。
     */
    String duplicateInsertRejection(String conversationId, String action, Map<String, Object> params) {
        String fingerprint = bulkInsertFingerprint(conversationId, action, params);
        if (fingerprint == null) return null;

        boolean fresh = dispatchedBulkInserts
                .computeIfAbsent(conversationId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(fingerprint);
        if (fresh) return null;

        log.warn("拦下本轮重复整段插入: action={}, conversationId={}", action, conversationId);
        return "{\"error\": \"相同内容本轮已插入过（" + action + "），不要重复插入。"
                + "若上一次调用返回超时，内容很可能已经写入文档——"
                + "请先用 doc_get_document_text / doc_read_paragraphs 读回确认，确认没写入再重试。\"}";
    }

    /**
     * 编辑器**明确报错**（不是超时）时把这次登记撤掉：那一次确实没写进文档，
     * 模型换个参数或等编辑器就绪后重试同一段内容不该被闸拦。超时不走这里——
     * 超时的结局未知，宁可挡住重发。
     */
    private void forgetBulkInsert(String conversationId, String action, Map<String, Object> params) {
        String fingerprint = bulkInsertFingerprint(conversationId, action, params);
        if (fingerprint == null) return;
        java.util.Set<String> seen = dispatchedBulkInserts.get(conversationId);
        if (seen != null) seen.remove(fingerprint);
    }

    /** 进闸的整段插入指纹（文档 + action + 整组参数）；不该进闸时返回 null。 */
    private String bulkInsertFingerprint(String conversationId, String action, Map<String, Object> params) {
        if (conversationId == null || action == null || params == null) return null;
        String textParam = BULK_INSERT_TEXT_PARAM.get(action);
        if (textParam == null) return null;
        Object text = params.get(textParam);
        if (!(text instanceof String body) || body.length() < BULK_INSERT_MIN_CHARS) return null;
        return sha256(activeDocKeys.getOrDefault(conversationId, "-")
                + "|" + action + "|" + new java.util.TreeMap<>(params));
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // JDK 保证有
        }
    }

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

    /**
     * 本轮流式写入到底有没有往文档里送出过正文（dev-board#465）。
     *
     * <p>「AI 说写好了、文档一片空白、全程无报错」有一半出在这里：模型把正文包进
     * {@code <artifact>}/{@code <process>} 之类协议标签（AgentStreamHandler 的
     * HIDDEN_CONTENT_TAGS 会整段吞掉），或者压根没输出文档正文，doc_stream_data 就只剩
     * 标签之间漏出来的空白——前端照样点亮「正在向文档流式写入内容…」，收尾照样报 finished。
     * 这个标记让编排器在流结束时能判定「一个字都没送出去」，把它当失败讲出来。
     */
    private final ConcurrentHashMap<String, Boolean> streamWroteContent = new ConcurrentHashMap<>();

    public void setStreamingMode(String conversationId, boolean enabled) {
        if (enabled) {
            streamingModes.put(conversationId, true);
            // 开启流式即重置本轮的「送出过正文」标记
            streamWroteContent.remove(conversationId);
        } else {
            streamingModes.remove(conversationId);
        }
    }

    public boolean isStreamingMode(String conversationId) {
        return streamingModes.getOrDefault(conversationId, false);
    }

    /** 记一笔「这个 token 里有非空白正文」；空白/空串不算（标签之间漏出来的换行不能算写过）。 */
    public void noteStreamContent(String conversationId, String token) {
        if (conversationId == null || token == null || token.isBlank()) return;
        streamWroteContent.put(conversationId, true);
    }

    /** 本轮流式写入是否送出过非空白正文。 */
    public boolean hasStreamedContent(String conversationId) {
        return streamWroteContent.getOrDefault(conversationId, false);
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
            Map<String, Object> fields = new java.util.HashMap<>();
            fields.put("fileId", file.getId());
            fields.put("fileName", file.getName());
            fields.put("fileType", file.getFileType());
            fields.put("wpsFileId", file.getWpsFileId() != null ? file.getWpsFileId() : "");
            fields.put("trackRevisions", true);
            fields.put("userName", "AI WorkDeck");
            // 项目有模板画像时随打开指令带下去（只在非 house-default 时），前端在该文件的编辑器
            // 就绪后追发 set_style_profile——worker 是按文档实例起的，画像必须打在打开后的那个
            // worker 上，不能在这里直接发 editor_command（会落到上一份文档或"编辑器未就绪"）。
            Map<String, Object> profile = styleProfileFor(file);
            if (profile != null) fields.put("styleProfile", profile);
            noteActiveDocument(conversationId, file.getId());
            sendDualNamedAction("doc_open_file", "wps_open_file", conversationId, fields);
            log.info("Sent doc_open_file action for file: {} (id={}, styleProfile={})", file.getName(), file.getId(), profile != null);

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
     * 通知前端重载纯文本标签（text_write_file / text_find_replace 后端直改之后）。
     * 单向、单名（dev-board#37 新增，没有 wps_* 时代的旧名要背）；前端只对
     * 「该文件正开着的文本标签」就地重载，没开着就什么都不做。
     */
    public void sendTextReloadFileAction(ProjectFile file) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send text reload file action");
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "action", "text_reload_file",
                    "fileId", file.getId(),
                    "fileName", file.getName()
            ));
            sseEmitterService.send(conversationId, "client_action", payload);
            log.info("Sent text_reload_file action for file: {} (id={})", file.getName(), file.getId());
        } catch (Exception e) {
            log.error("Failed to send text reload file action", e);
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
     * 单名 client_action（无双轨旧名）：显式指定会话，不依赖 ThreadLocal 的当前会话——
     * 插件后台任务跑在自己的线程池上，这里拿不到 currentConversationId。
     * 载荷 = fields + {action}。
     */
    public void sendClientAction(String action, String conversationId, Map<String, Object> fields) {
        if (action == null || conversationId == null) return;
        try {
            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>(fields != null ? fields : Map.of());
            payloadMap.put("action", action);
            sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
        } catch (Exception e) {
            log.warn("Failed to send client_action {} to {}: {}", action, conversationId, e.getMessage());
        }
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

        // 本轮重复整段插入直接拦下，一个 worker 命令都不发（dev-board#464）
        String duplicate = duplicateInsertRejection(conversationId, action, params);
        if (duplicate != null) {
            recordBridge(action, "duplicate", conversationId, System.currentTimeMillis());
            return duplicate;
        }
        if ("doc_open_file_sync".equals(action) && params != null) {
            noteActiveDocument(conversationId, params.get("fileId"));
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<EditorActionResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, new PendingAction(conversationId, future));
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
            EditorActionResult result = future.get(timeoutSecondsFor(action), TimeUnit.SECONDS);
            
            if (result.isSuccess()) {
                recordBridge(action, "ok", conversationId, bridgeStartMs);
                return objectMapper.writeValueAsString(result.getData());
            } else {
                recordBridge(action, "error", conversationId, bridgeStartMs);
                // 编辑器明确报错 = 这段确实没写进去，撤掉去重登记（超时不撤，结局未知）
                forgetBulkInsert(conversationId, action, params);
                return "{\"error\": \"" + result.getError() + "\"}";
            }

        } catch (TimeoutException e) {
            log.warn("Editor command timed out: action={}, requestId={}", action, requestId);
            recordBridge(action, "timeout", conversationId, bridgeStartMs);
            return TIMEOUT_RESULT_JSON;

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
    public boolean completeEditorAction(String requestId, String conversationId,
                                        boolean success, Object data, String error) {
        PendingAction pending = pendingRequests.get(requestId);
        if (pending == null) {
            log.warn("No pending request found for requestId={}", requestId);
            return false;
        }
        if (conversationId == null || !conversationId.equals(pending.conversationId())) {
            log.warn("Rejected editor result: requestId={} belongs to another conversation", requestId);
            return false;
        }
        pending.future().complete(new EditorActionResult(success, data, error));
        log.info("Completed editor action: requestId={}, success={}", requestId, success);
        return true;
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

