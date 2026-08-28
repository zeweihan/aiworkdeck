package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.model.entity.MobileTransferRequest;
import com.checkba.service.mobile.MobileRelayStoreService;
import com.checkba.service.mobile.MobileTransferService;
import com.checkba.service.mobile.TransferBillingClient;
import lombok.Data;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨设备文件传输（dev-board#251，spec 见
 * docs/superpowers/specs/2026-08-28-cross-device-transfer.md 二、2.2）。
 *
 * <p>鉴权同 {@link MobileRelayController} 那一组：{@code X-Session-Id}。
 * 错误一律 {@code IllegalArgumentException} → 全局处理器翻成 HTTP 200 + {code:1,message}
 * （现网中转约定），鉴权失败用 {@link UnauthorizedException} 走 4010 信封。
 */
@RestController
@RequestMapping("/api/mobile/transfer")
public class MobileTransferController {

    private final MobileTransferService service;
    private final MobileRelayStoreService store;

    public MobileTransferController(MobileTransferService service, MobileRelayStoreService store) {
        this.service = service;
        this.store = store;
    }

    // ==================== 发起端（A） ====================

    /** GET /quote?bytes=N */
    @GetMapping("/quote")
    public Map<String, Object> quote(
            @RequestParam("bytes") long bytes,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        TransferBillingClient.QuoteResult q = service.quote(userId, bytes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("credits", q.credits());
        out.put("balanceCents", q.balanceCents());
        return out;
    }

    @Data
    public static class ListRequest {
        private String deviceId;
        private String projectKey;
        private String requestId;
    }

    @PostMapping("/list")
    public Map<String, Object> list(
            @RequestBody ListRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileTransferRequest row = service.list(userId, request.getDeviceId(), request.getProjectKey(), request.getRequestId());
        return Map.of("code", 0, "id", row.getId());
    }

    /** GET /{id}：service.get 已经带 code:0 信封。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.get(requireUser(sessionId), id);
    }

    @Data
    public static class PullRequest {
        private String deviceId;
        private String projectKey;
        private String remoteFileId;
        private String fileName;
        private Long fileSize;
        private String requestId;
    }

    @PostMapping("/pull")
    public Map<String, Object> pull(
            @RequestBody PullRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileTransferRequest row = service.pull(userId, request.getDeviceId(), request.getProjectKey(),
                request.getRemoteFileId(), request.getFileName(),
                request.getFileSize() == null ? 0 : request.getFileSize(), request.getRequestId());
        return Map.of("code", 0, "id", row.getId(), "credits", row.getChargedCredits());
    }

    @Data
    public static class SaveToProjectRequest {
        private Long projectId;
    }

    @PostMapping("/{id}/save-to-project")
    public Map<String, Object> saveToProject(
            @PathVariable("id") Long id,
            @RequestBody SaveToProjectRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        Map<String, Object> result = service.saveToProject(userId, id, request.getProjectId());
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("code", 0);
        return out;
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        service.cancel(requireUser(sessionId), id);
        return Map.of("code", 0);
    }

    @Data
    public static class PushRequest {
        private String targetDeviceId;
        private String projectKey;
        private Long fileId;
        private String requestId;
    }

    @PostMapping("/push")
    public Map<String, Object> push(
            @RequestBody PushRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileTransferRequest row = service.push(userId, request.getTargetDeviceId(), request.getProjectKey(),
                request.getFileId(), request.getRequestId());
        return Map.of("code", 0, "id", row.getId(), "credits", row.getChargedCredits());
    }

    // ==================== 响应端（B） ====================

    /** GET /commands：同时是心跳（照抄 /inbox 的做法）。 */
    @GetMapping("/commands")
    public Map<String, Object> commands(
            @RequestParam("deviceId") String deviceId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        store.touchDevice(userId, deviceId);
        MobileTransferService.CommandsResult result = service.commands(userId, deviceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("commands", result.commands());
        out.put("hot", result.hot());
        return out;
    }

    @Data
    public static class FilesRequest {
        private List<Map<String, Object>> files;
    }

    @PostMapping("/{id}/files")
    public Map<String, Object> files(
            @PathVariable("id") Long id,
            @RequestBody FilesRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        service.submitFiles(requireUser(sessionId), id, request.getFiles());
        return Map.of("code", 0);
    }

    @PostMapping("/{id}/upload")
    public Map<String, Object> upload(
            @PathVariable("id") Long id,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of("未找到文件", "No file provided"));
        }
        try (InputStream in = file.getInputStream()) {
            service.upload(userId, id, in, file.getSize());
        } catch (IOException e) {
            throw new IllegalStateException(com.checkba.service.LangText.of("文件暂存失败", "Failed to stage the file"), e);
        }
        return Map.of("code", 0);
    }

    /** 契约红线同 /inbox/{id}/content：成功必须是 2xx + application/octet-stream + 裸字节。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileTransferService.ContentBlob blob = service.content(userId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .contentLength(blob.length())
                .body(new InputStreamResource(blob.stream()));
    }

    @PostMapping("/{id}/ack")
    public Map<String, Object> ack(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        service.ack(requireUser(sessionId), id);
        return Map.of("code", 0);
    }

    @Data
    public static class FailRequest {
        private String message;
    }

    @PostMapping("/{id}/fail")
    public Map<String, Object> fail(
            @PathVariable("id") Long id,
            @RequestBody FailRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        service.fail(requireUser(sessionId), id, request.getMessage());
        return Map.of("code", 0);
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }
}
