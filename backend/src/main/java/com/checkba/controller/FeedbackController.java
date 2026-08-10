package com.checkba.controller;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.User;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.feedback.FeedbackService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈入口（桌面端右下角浮窗提交）。
 *
 * <p>提交只需要一个能解析出来的用户（local-mode 下恒成立）；查看列表/附件需要管理员，
 * 因为反馈里带着截图与日志尾巴，属于别人的运行现场。
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final com.checkba.service.feedback.FeedbackUploadService uploadService;
    private final UserFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${feedback.optimizer-token:}")
    private String optimizerToken;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submit(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            // payload 用 @RequestParam 而不是 @RequestPart：浏览器 FormData 里的普通文本字段
            // 没有 Content-Type，@RequestPart 会因为找不到 HttpMessageConverter 而 415
            @RequestParam(value = "payload", required = false) String payloadJson,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }
        try {
            Map<String, Object> payload = parsePayload(payloadJson);
            FeedbackService.SubmitRequest req = new FeedbackService.SubmitRequest(
                    str(payload.get("kind")),
                    str(payload.get("text")),
                    asLong(payload.get("projectId")),
                    str(payload.get("page")),
                    asMap(payload.get("clientContext")));
            UserFeedback fb = feedbackService.submit(userId, req, files);
            // 立刻推一次云端收件箱（异步，用户不等网络）；失败留给 30 分钟的补传轮
            uploadService.flushAsync();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", fb.getId());
            data.put("status", fb.getStatus());
            data.put("attachments", feedbackService.attachmentsOf(fb.getId()).size());
            data.put("transcribed", fb.getVoiceTranscript() != null && !fb.getVoiceTranscript().isBlank());
            return ResponseEntity.ok(success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(e.getMessage()));
        } catch (Exception e) {
            log.error("反馈提交失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("提交失败，请稍后再试"));
        }
    }

    /**
     * 用户自己提交过的反馈（浮窗「我的反馈」视图用）：只回当前 session 解析出的
     * userId 自己的行，不需要管理员——这和 {@link #list} / {@link #detail} 不同，
     * 那两个要看别人的现场（截图/日志），这个只让人看自己报过什么。
     */
    @GetMapping("/mine")
    public ResponseEntity<?> mine(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserFeedback fb : feedbackService.listByUser(userId, 100)) {
            items.add(mineBrief(fb));
        }
        return ResponseEntity.ok(success(Map.of("items", items)));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        if (requireAdmin(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("需要管理员权限"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserFeedback fb : feedbackService.list(status, limit)) {
            items.add(brief(fb));
        }
        return ResponseEntity.ok(success(Map.of("items", items)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id) {
        if (requireAdmin(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("需要管理员权限"));
        }
        UserFeedback fb = feedbackRepository.findById(id).orElse(null);
        if (fb == null) return ResponseEntity.ok(error("反馈不存在"));
        Map<String, Object> data = new LinkedHashMap<>(brief(fb));
        data.put("contextJson", fb.getContextJson());
        data.put("triageJson", fb.getTriageJson());
        List<Map<String, Object>> atts = new ArrayList<>();
        for (FeedbackAttachment a : feedbackService.attachmentsOf(id)) {
            atts.add(Map.of(
                    "id", a.getId(),
                    "type", a.getType(),
                    "name", a.getOriginalName() == null ? a.getStoredName() : a.getOriginalName(),
                    "contentType", a.getContentType() == null ? "" : a.getContentType(),
                    "sizeBytes", a.getSizeBytes()));
        }
        data.put("attachments", atts);
        return ResponseEntity.ok(success(data));
    }

    @GetMapping("/{id}/attachment/{attachmentId}")
    public ResponseEntity<Resource> attachment(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Optimizer-Token", required = false) String optimizerToken,
            @RequestParam(value = "token", required = false) String token,
            @PathVariable Long id,
            @PathVariable Long attachmentId) {
        String effective = (sessionId == null || sessionId.isEmpty()) ? token : sessionId;
        // 优化者的邮件里给的是这条 URL；能取全部待办反馈的那把 token 也该能取它们的附件，
        // 否则维护者收到一封「这里有张截图」的信却打不开（token 走 header，不进 URL）
        boolean viaOptimizer = optimizerTokenOk(optimizerToken);
        if (!viaOptimizer && requireAdmin(effective) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            Resource resource = feedbackService.readAttachment(id, attachmentId);
            if (!resource.exists()) return ResponseEntity.notFound().build();
            String name = resource.getFilename() == null ? "attachment" : resource.getFilename();
            return ResponseEntity.ok()
                    .contentType(mediaTypeOf(name))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, Object> brief(UserFeedback fb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", fb.getId());
        m.put("userId", fb.getUserId());
        // 后台要能回答「哪个用户提的」；userId 对人没意义，这里解析成用户名
        m.put("username", fb.getUserId() == null ? "" : userRepository.findById(fb.getUserId())
                .map(User::getUsername).orElse("用户 #" + fb.getUserId()));
        m.put("projectId", fb.getProjectId());
        m.put("kind", fb.getKind());
        m.put("text", fb.getText());
        m.put("voiceTranscript", fb.getVoiceTranscript());
        m.put("page", fb.getPage());
        m.put("appVersion", fb.getAppVersion());
        m.put("platform", fb.getPlatform());
        m.put("status", fb.getStatus());
        m.put("triageVerdict", fb.getTriageVerdict());
        m.put("prUrl", fb.getPrUrl());
        m.put("lastError", fb.getLastError());
        m.put("attempts", fb.getAttempts());
        m.put("createdAt", fb.getCreatedAt() == null ? null : fb.getCreatedAt().toString());
        m.put("handledAt", fb.getHandledAt() == null ? null : fb.getHandledAt().toString());
        return m;
    }

    /** {@link #brief} 去掉 lastError 这种内部字段，加上 uploaded——
     * 用户视角要能分清「待发送」（还没传到云端）与「已送达」（云端已收到，排队处理中）。 */
    private Map<String, Object> mineBrief(UserFeedback fb) {
        Map<String, Object> m = brief(fb);
        m.remove("lastError");
        m.put("uploaded", fb.isUploaded());
        return m;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("提交内容格式有误");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static MediaType mediaTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webm")) return MediaType.parseMediaType("audio/webm");
        if (lower.endsWith(".ogg")) return MediaType.parseMediaType("audio/ogg");
        if (lower.endsWith(".m4a")) return MediaType.parseMediaType("audio/mp4");
        if (lower.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private boolean optimizerTokenOk(String token) {
        // 与取件/回执同一把钥匙；没配就当不存在（不留「未配置即放行」）
        if (optimizerToken == null || optimizerToken.isBlank() || token == null) return false;
        return java.security.MessageDigest.isEqual(
                optimizerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private User requireAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return null;
        return userRepository.findById(userId).filter(adminAccessService::isAdmin).orElse(null);
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
