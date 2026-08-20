package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.service.LangText;
import com.checkba.service.mobile.MobileRelayStoreService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端云中转（spec：aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md）。
 *
 * <p>鉴权一律 {@code X-Session-Id}：手机端带登录会话，桌面端带 awdt_ 设备令牌，
 * {@link AuthController#getUserIdFromSession} 两种都解析。响应风格与
 * {@code /api/projects/my} 一致（裸数组/裸对象，不带 code 信封）。
 */
@RestController
@RequestMapping("/api/mobile")
@Slf4j
public class MobileRelayController {

    private final MobileRelayStoreService store;

    public MobileRelayController(MobileRelayStoreService store) {
        this.store = store;
    }

    @Data
    public static class DirectoryRequest {
        private String deviceId;
        private String deviceName;
        private List<Entry> projects;

        @Data
        public static class Entry {
            private String key;
            private String name;
        }
    }

    /** 桌面端：项目目录全量替换（按 userId + deviceId）。 */
    @PutMapping("/projects")
    public Map<String, Object> replaceDirectory(
            @RequestBody DirectoryRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        List<MobileRelayStoreService.DirEntry> entries = new ArrayList<>();
        if (request.getProjects() != null) {
            for (DirectoryRequest.Entry e : request.getProjects()) {
                if (e != null) entries.add(new MobileRelayStoreService.DirEntry(e.getKey(), e.getName()));
            }
        }
        store.replaceDirectory(userId, request.getDeviceId(), request.getDeviceName(), entries);
        return Map.of("code", 0, "count", entries.size());
    }

    /** 手机端：该账号全部设备的项目目录并集（裸数组）。 */
    @GetMapping("/projects")
    public List<Map<String, Object>> listDirectory(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return store.listDirectory(requireUser(sessionId));
    }

    /** 手机端：上传一件现场影像到中转区（幂等键 clientMediaId）。 */
    @PostMapping("/media")
    public Map<String, Object> uploadMedia(
            @RequestPart("file") MultipartFile file,
            @RequestParam("deviceId") String deviceId,
            @RequestParam("projectKey") String projectKey,
            @RequestParam("clientMediaId") String clientMediaId,
            @RequestParam("fileName") String fileName,
            @RequestParam("mediaType") String mediaType,
            @RequestParam(value = "capturedAt", required = false) String capturedAt,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("未找到文件", "No file provided"));
        }
        LocalDateTime captured = parseCapturedAt(capturedAt);
        try (InputStream in = file.getInputStream()) {
            MobileMediaInbox item = store.storeMedia(
                    userId, deviceId, projectKey, clientMediaId, fileName, mediaType, captured, in);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 0);
            out.put("id", item.getId());
            out.put("clientMediaId", item.getClientMediaId());
            out.put("delivered", item.getDeliveredAt() != null);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("影像暂存失败", "Failed to store media"), e);
        }
    }

    /** 手机端：查询影像投递状态（裸数组）。 */
    @GetMapping("/media/status")
    public List<Map<String, Object>> mediaStatus(
            @RequestParam("clientMediaIds") String clientMediaIds,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        List<String> ids = new ArrayList<>();
        for (String id : clientMediaIds.split(",")) {
            if (!id.isBlank()) ids.add(id.trim());
        }
        return store.status(userId, ids);
    }

    /** 桌面端：本设备待取件（元数据，裸数组）。 */
    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox(
            @RequestParam("deviceId") String deviceId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MobileMediaInbox item : store.pendingForDevice(userId, deviceId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("projectKey", item.getProjectKey());
            m.put("clientMediaId", item.getClientMediaId());
            m.put("fileName", item.getFileName());
            m.put("mediaType", item.getMediaType());
            m.put("fileSize", item.getFileSize());
            m.put("capturedAt", item.getCapturedAt() != null ? item.getCapturedAt().toString() : null);
            m.put("createdAt", item.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 桌面端：取件字节流。 */
    @GetMapping("/inbox/{id}/content")
    public ResponseEntity<FileSystemResource> content(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        FileSystemResource resource = new FileSystemResource(store.contentPath(userId, id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(resource);
    }

    /** 桌面端：确认落盘。置 deliveredAt 并立即删除 blob。 */
    @PostMapping("/inbox/{id}/ack")
    public Map<String, Object> ack(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        store.ack(requireUser(sessionId), id);
        return Map.of("code", 0);
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }

    private static LocalDateTime parseCapturedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // 手机端发 ISO-8601；带时区偏移的转成服务器本地时间，裸的按原样收
            if (value.contains("+") || value.endsWith("Z")) {
                return java.time.OffsetDateTime.parse(value)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
            }
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null; // 拍摄时间是元数据，格式不对不该挡住上传
        }
    }
}
