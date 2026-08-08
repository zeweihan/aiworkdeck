package com.checkba.controller;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackIngestGuard;
import com.checkba.service.feedback.FeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端收件箱：接收各安装上传的反馈，以及给**跑在别处的优化者**取件/回执。
 *
 * <p>两组端点两套闸：
 * <ul>
 *   <li>{@code POST /api/feedback/ingest} —— 公网开放（见 {@link FeedbackIngestGuard} 里
 *       「为什么不要求登录」），闸门是配额与体积；</li>
 *   <li>{@code /api/feedback/pending}、{@code /api/feedback/{id}/resolution} —— 给优化者用，
 *       共享密钥鉴权（{@code feedback.optimizer-token}）。**密钥没配就整组 403**，
 *       绝不留「没配 = 不校验」的逃生门。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackIngestController {

    private final FeedbackService feedbackService;
    private final FeedbackIngestGuard guard;
    private final UserFeedbackRepository feedbackRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${feedback.optimizer-token:}")
    private String optimizerToken;

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(
            @RequestParam(value = "payload", required = false) String payloadJson,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        Map<String, Object> payload;
        try {
            payload = payloadJson == null || payloadJson.isBlank()
                    ? Map.of() : mapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            return ResponseEntity.ok(error("提交内容格式有误"));
        }
        String installId = str(payload.get("installId"));
        String clientRef = str(payload.get("clientRef"));
        try {
            guard.check(installId, files);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error(e.getMessage()));
        }
        if (clientRef == null || clientRef.isBlank()) {
            return ResponseEntity.ok(error("缺少 clientRef"));
        }
        try {
            UserFeedback fb = feedbackService.ingest(installId, clientRef,
                    new FeedbackService.IngestRequest(
                            str(payload.get("kind")), str(payload.get("text")),
                            str(payload.get("voiceTranscript")), str(payload.get("page")),
                            str(payload.get("appVersion")), str(payload.get("platform")),
                            str(payload.get("contextJson"))),
                    files);
            return ResponseEntity.ok(success(Map.of("id", fb.getId())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(e.getMessage()));
        } catch (Exception e) {
            log.error("反馈收件失败 installId={}", installId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("收件失败"));
        }
    }

    /** 优化者取件：待分诊的反馈（含附件清单，正文与现场一并带出）。 */
    @GetMapping("/pending")
    public ResponseEntity<?> pending(
            @RequestHeader(value = "X-Optimizer-Token", required = false) String token,
            @RequestParam(value = "limit", required = false, defaultValue = "5") int limit,
            @RequestParam(value = "maxAttempts", required = false, defaultValue = "3") int maxAttempts) {
        if (!tokenOk(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("token 不对"));
        List<UserFeedback> rows = feedbackRepository.findByStatusAndAttemptsLessThanOrderByIdAsc(
                UserFeedback.STATUS_NEW, maxAttempts, PageRequest.of(0, Math.max(1, Math.min(50, limit))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserFeedback fb : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", fb.getId());
            m.put("kind", fb.getKind());
            m.put("text", fb.getText());
            m.put("voiceTranscript", fb.getVoiceTranscript());
            m.put("page", fb.getPage());
            m.put("appVersion", fb.getAppVersion());
            m.put("platform", fb.getPlatform());
            m.put("contextJson", fb.getContextJson());
            m.put("attempts", fb.getAttempts());
            m.put("createdAt", fb.getCreatedAt() == null ? null : fb.getCreatedAt().toString());
            List<Map<String, Object>> atts = new ArrayList<>();
            for (FeedbackAttachment a : feedbackService.attachmentsOf(fb.getId())) {
                atts.add(Map.of("id", a.getId(), "type", a.getType(),
                        "name", a.getStoredName(), "sizeBytes", a.getSizeBytes()));
            }
            m.put("attachments", atts);
            items.add(m);
        }
        return ResponseEntity.ok(success(Map.of("items", items)));
    }

    /** 优化者回执：把分诊结论与去向写回云端这条记录。 */
    @PostMapping("/{id}/resolution")
    public ResponseEntity<?> resolution(
            @RequestHeader(value = "X-Optimizer-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        if (!tokenOk(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("token 不对"));
        UserFeedback fb = feedbackRepository.findById(id).orElse(null);
        if (fb == null) return ResponseEntity.ok(error("反馈不存在"));

        String status = str(body.get("status"));
        if (status != null && !status.isBlank()) fb.setStatus(status);
        if (body.containsKey("triageVerdict")) fb.setTriageVerdict(str(body.get("triageVerdict")));
        if (body.containsKey("triageJson")) fb.setTriageJson(str(body.get("triageJson")));
        if (body.containsKey("prUrl")) fb.setPrUrl(str(body.get("prUrl")));
        if (body.containsKey("lastError")) fb.setLastError(str(body.get("lastError")));
        if (body.get("attempts") instanceof Number n) fb.setAttempts(n.intValue());
        if (!UserFeedback.STATUS_NEW.equals(fb.getStatus())) fb.setHandledAt(LocalDateTime.now());
        feedbackRepository.save(fb);
        return ResponseEntity.ok(success(Map.of("id", fb.getId(), "status", fb.getStatus())));
    }

    /** 收件箱自身的状态（不含任何反馈内容，可给运维脚本探活）。 */
    @GetMapping("/inbox/status")
    public ResponseEntity<?> inboxStatus(
            @RequestHeader(value = "X-Optimizer-Token", required = false) String token) {
        if (!tokenOk(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("token 不对"));
        return ResponseEntity.ok(success(Map.of(
                "ingestEnabled", guard.isEnabled(),
                "last24h", guard.todayCount(),
                "pending", guard.pendingCount())));
    }

    private boolean tokenOk(String token) {
        // 没配 token 就整组关闭：留「未配置即放行」等于把取件与回执接口对公网敞开
        if (optimizerToken == null || optimizerToken.isBlank()) return false;
        return java.security.MessageDigest.isEqual(
                optimizerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (token == null ? "" : token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 1);
        r.put("message", message);
        r.put("data", new HashMap<>());
        return r;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("message", "OK");
        r.put("data", data);
        return r;
    }
}
