// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ai.memory.document.MemoryDocumentException;
import com.checkba.service.ai.memory.document.MemoryDocumentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/memory")
public class MemoryDocumentController {
    private final MemoryDocumentService service;

    public MemoryDocumentController(MemoryDocumentService service) { this.service = service; }

    @GetMapping("/spaces")
    public Map<String, Object> spaces(@RequestParam(required = false) Long projectId,
                                      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ok(service.listSpaces(user(sessionId), projectId));
    }

    @GetMapping("/files")
    public Map<String, Object> files(@RequestParam String spaceId,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ok(service.listFiles(user(sessionId), spaceId));
    }

    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String spaceId, @RequestParam String path,
                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ok(service.read(user(sessionId), spaceId, path));
    }

    @PutMapping("/file")
    public Map<String, Object> write(@RequestBody FileWrite body,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ok(service.write(user(sessionId), body.spaceId(), body.path(), body.content(),
                body.expectedRevision() == null ? -1 : body.expectedRevision()));
    }

    @DeleteMapping("/file")
    public Map<String, Object> delete(@RequestParam String spaceId, @RequestParam String path,
                                     @RequestParam long expectedRevision,
                                     @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        service.delete(user(sessionId), spaceId, path, expectedRevision);
        return ok(Map.of("deleted", true));
    }

    @GetMapping("/download")
    public ResponseEntity<String> download(@RequestParam String spaceId, @RequestParam String path,
                                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String content = service.download(user(sessionId), spaceId, path);
        String name = path.substring(path.lastIndexOf('/') + 1).replace("\"", "");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(content);
    }

    @ExceptionHandler(MemoryDocumentException.class)
    public ResponseEntity<Map<String, Object>> memoryError(MemoryDocumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.status());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    private static Long user(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new MemoryDocumentException(401, "请先登录");
        return userId;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("data", data);
        return body;
    }

    public record FileWrite(String spaceId, String path, String content, Long expectedRevision) {}
}
