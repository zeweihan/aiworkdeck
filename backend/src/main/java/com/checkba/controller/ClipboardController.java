// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.ClipboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/clipboard")
@RequiredArgsConstructor
public class ClipboardController {

    private final ClipboardService clipboardService;

    /**
     * 单机模式判别位。file-local 让调用方指名服务器磁盘上的绝对路径去读，只有「服务器就是
     * 用户这台电脑」时才成立，与 ProjectFileController.import-local 同一道闸。
     */
    @org.springframework.beans.factory.annotation.Value("${security.local-mode:false}")
    private boolean localMode;

    /** 单文件上限，与 POST /file 的 multipart 上限同一口径（application.yml spring.servlet.multipart）。 */
    @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-file-size:20GB}")
    private org.springframework.util.unit.DataSize maxFileSize = org.springframework.util.unit.DataSize.ofGigabytes(20);

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        return ResponseEntity.ok(clipboardService.list(userId, q, limit));
    }

    @PostMapping("/text")
    public ResponseEntity<?> saveText(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody SaveTextRequest request
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        return ResponseEntity.ok(success(clipboardService.saveText(userId, request.getText())));
    }

    @PostMapping("/file")
    public ResponseEntity<?> saveFile(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "type", required = false) String type
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        try {
            return ResponseEntity.ok(success(clipboardService.saveFile(userId, file, type)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(com.checkba.service.LangText.of("保存失败: ", "Save failed: ") + e.getMessage()));
        }
    }

    /**
     * 按本机路径存一份剪贴板文件（dev-board B14）。桌面端复制了一个文件时前端只传路径，
     * 服务端自己 copy 进剪贴板库——此前渲染进程要先把整个文件读进内存再 POST 回来。
     * POST /api/clipboard/file-local  body: { sourcePath }
     */
    @PostMapping("/file-local")
    public ResponseEntity<?> saveLocalFile(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody SaveLocalFileRequest request
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        if (!localMode) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(com.checkba.service.LangText.of(
                    "当前部署不支持按本机路径保存剪贴板文件", "This deployment does not support saving clipboard files from a local path")));
        }
        try {
            return ResponseEntity.ok(success(clipboardService.saveLocalFile(
                    userId, request == null ? null : request.getSourcePath(), maxFileSize.toBytes())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(com.checkba.service.LangText.of("保存失败: ", "Save failed: ") + e.getMessage()));
        }
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<org.springframework.core.io.Resource> getFile(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "token", required = false) String token,
            @PathVariable Long id
    ) {
        String effectiveToken = sessionId;
        if (effectiveToken == null || effectiveToken.isEmpty()) {
            effectiveToken = token;
        }
        Long userId = AuthController.getUserIdFromSession(effectiveToken);
        if (userId == null) {
             return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        try {
            org.springframework.core.io.Resource resource = clipboardService.getFile(id, userId);
            String filename = resource.getFilename();
            if (filename == null) filename = "file";
            
            org.springframework.http.MediaType mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) mediaType = org.springframework.http.MediaType.IMAGE_PNG;
            else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mediaType = org.springframework.http.MediaType.IMAGE_JPEG;
            else if (lower.endsWith(".gif")) mediaType = org.springframework.http.MediaType.IMAGE_GIF;
            
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        clipboardService.delete(id, userId);
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", com.checkba.service.LangText.of("删除成功", "Deleted successfully"));
        ok.put("data", new HashMap<>());
        return ResponseEntity.ok(ok);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        result.put("data", new HashMap<>());
        return result;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "OK");
        result.put("data", data);
        return result;
    }

    public static class SaveLocalFileRequest {
        private String sourcePath;

        public String getSourcePath() {
            return sourcePath;
        }

        public void setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
        }
    }

    public static class SaveTextRequest {
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

