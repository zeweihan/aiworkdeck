// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.document.DocumentGeneratorSettings;
import com.checkba.util.ProductIdentity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档 Generator 元数据开关的读写口（可溯源性设计规范附录 B4）。
 *
 * <p>GET 给设置页与编辑器保存路径共用：{@code application} 就是要写进 docProps/app.xml
 * 的那个串，前端不许自己拼版本号。鉴权口径照 {@link TelemetryController}——读不设门槛
 * （返回的只有一个布尔与产品名），写要求已登录。
 */
@RestController
@RequestMapping("/api/document/generator")
@RequiredArgsConstructor
public class DocumentGeneratorController {

    private final DocumentGeneratorSettings settings;

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("enabled", settings.enabled());
        r.put("application", ProductIdentity.applicationName());
        return r;
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(
            @RequestBody SettingsRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (request.getEnabled() != null) settings.setEnabled(request.getEnabled());
        return getSettings();
    }

    @Data
    public static class SettingsRequest {
        private Boolean enabled;
    }
}
