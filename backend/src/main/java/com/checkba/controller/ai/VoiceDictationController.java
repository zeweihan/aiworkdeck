package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.LangText;
import com.checkba.service.ai.VoiceDictationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音听写端点（dev-board#153，Office 插件麦克风输入）。
 * POST /api/voice/dictate {audioBase64, format:"wav"|"mp3", durationMs} → {code:0, text}
 * 转写路径与计费口径见 {@link VoiceDictationService}。
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceDictationController {

    private final VoiceDictationService voiceDictationService;

    @PostMapping("/dictate")
    public ResponseEntity<?> dictate(@RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).body(LangText.of("连接未就绪或令牌无效", "Connection not ready or token invalid"));
        }
        String audioBase64 = body == null ? null : String.valueOf(body.getOrDefault("audioBase64", ""));
        String format = body == null ? "" : String.valueOf(body.getOrDefault("format", "wav"));
        long durationMs;
        try {
            durationMs = body == null ? 0 : Long.parseLong(String.valueOf(body.getOrDefault("durationMs", "0")));
        } catch (NumberFormatException e) {
            durationMs = 0;
        }
        try {
            VoiceDictationService.Dictation result = voiceDictationService.transcribe(userId, audioBase64, format, durationMs);
            return ResponseEntity.ok(Map.of("code", 0, "text", result.text()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(e.getMessage());
        }
    }
}
