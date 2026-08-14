package com.checkba.controller;

import com.checkba.service.AppLanguageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用语言设置。鉴权口径同 TelemetryController：GET 无鉴权（启动链在登录前就要读），
 * POST 要求登录（桌面 local-mode 自动解析为本机用户，非 admin 也可改——语言是
 * 每个用户都该能改的设置，刻意不走 /api/admin/config 的 requireAdmin 通道）。
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppLanguageController {

    private final AppLanguageService appLanguage;

    @GetMapping("/language")
    public Map<String, Object> getLanguage() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("language", appLanguage.language());
        return r;
    }

    @PostMapping("/language")
    public Map<String, Object> setLanguage(
            @RequestBody LanguageRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        appLanguage.setLanguage(request == null ? null : request.getLanguage());
        return getLanguage();
    }

    @Data
    public static class LanguageRequest {
        private String language;
    }
}
