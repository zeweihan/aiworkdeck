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

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }
        try {
            return ResponseEntity.ok(success(clipboardService.saveFile(userId, file, type)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("保存失败: " + e.getMessage()));
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }
        clipboardService.delete(id, userId);
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "删除成功");
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

