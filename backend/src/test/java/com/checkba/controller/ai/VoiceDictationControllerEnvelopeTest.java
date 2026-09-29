// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.account.AccountRequired;
import com.checkba.service.ai.VoiceDictationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 听写端点的失败形状（登录后置，dev-board#1046）：原来是 HTTP 400 / 502 + 纯文本，
 * 现在一律 HTTP 200 + JSON 信封；没有账户是 4011。
 */
class VoiceDictationControllerEnvelopeTest {

    private VoiceDictationService service;
    private VoiceDictationController controller;

    @BeforeEach
    void setUp() {
        // local-mode：任何请求都解析成本机用户（与桌面端一致）
        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.isLocalMode()).thenReturn(true);
        when(identity.localUserId()).thenReturn(7L);
        AuthController.registerLocalIdentityService(identity);
        service = mock(VoiceDictationService.class);
        controller = new VoiceDictationController(service);
    }

    @AfterEach
    void tearDown() {
        AuthController.registerLocalIdentityService(null);
    }

    private static final Map<String, Object> BODY = Map.of("audioBase64", "AAAA", "format", "wav", "durationMs", 1000);

    @SuppressWarnings("unchecked")
    private Map<String, Object> call() {
        ResponseEntity<?> res = controller.dictate(BODY, null);
        assertEquals(200, res.getStatusCode().value());
        return (Map<String, Object>) res.getBody();
    }

    @Test
    @DisplayName("没有账户 → HTTP 200 + 4011（reason=dictation）")
    void noAccountIs4011() {
        when(service.transcribe(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(AccountRequired.exception(AccountRequired.REASON_DICTATION, "需要连接账户"));
        Map<String, Object> body = call();
        assertEquals(4011, body.get("code"));
        assertEquals("NOT_CONNECTED", body.get("kind"));
        assertEquals("dictation", body.get("reason"));
    }

    @Test
    @DisplayName("通道故障（原 502 纯文本）→ HTTP 200 + code=1 JSON")
    void upstreamFailureIsJson() {
        when(service.transcribe(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("听写服务暂时不可达"));
        Map<String, Object> body = call();
        assertEquals(1, body.get("code"));
        assertEquals("听写服务暂时不可达", body.get("message"));
    }

    @Test
    @DisplayName("参数问题（原 400 纯文本）→ HTTP 200 + code=1 JSON")
    void badInputIsJson() {
        when(service.transcribe(anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalArgumentException("音频格式仅支持 wav/mp3"));
        Map<String, Object> body = call();
        assertEquals(1, body.get("code"));
    }
}
