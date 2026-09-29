// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.LangText;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountRequired;
import com.checkba.service.ai.VoiceDictationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音听写端点（dev-board#153，Office 插件麦克风输入）。
 * POST /api/voice/dictate {audioBase64, format:"wav"|"mp3", durationMs} → {code:0, text}
 * 转写路径与计费口径见 {@link VoiceDictationService}。
 *
 * <p>失败形状与全站一致（登录后置，dev-board#1046）：HTTP 200 + JSON 信封。
 * 参数问题与通道故障是 {@code {code:1, message}}，没有账户是 4011
 * {@code {code:4011, kind:"NOT_CONNECTED", reason:"dictation", message}}。
 * 原来这两类分别是 HTTP 400 / 502 + 纯文本 body，调用方只能靠状态码猜。
 * 只有「会话/令牌根本不成立」仍是 HTTP 401——那是连接层的问题，不是业务错误。
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
        } catch (AccountException e) {
            return ResponseEntity.ok(AccountRequired.envelope(e));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 1);
            err.put("message", e.getMessage());
            return ResponseEntity.ok(err);
        }
    }
}
