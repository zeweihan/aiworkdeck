// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.mobile.MobileRelayClientService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 本机收件进度；云端不注册，也不暴露设备令牌。 */
@RestController
@RequestMapping("/api/mobile-receive")
@ConditionalOnProperty(name = "security.local-mode", havingValue = "true")
public class MobileReceiveController {
    private final MobileRelayClientService client;

    public MobileReceiveController(MobileRelayClientService client) {
        this.client = client;
    }

    private void requireUser(String session) {
        if (AuthController.getUserIdFromSession(session) == null) throw new IllegalStateException("请先登录");
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Session-Id", required = false) String session,
            @RequestParam(value = "projectKey", required = false) String projectKey) {
        requireUser(session);
        return client.receiveStatus(projectKey);
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestHeader(value = "X-Session-Id", required = false) String session) {
        requireUser(session);
        client.receiveNow();
        return Map.of("code", 0);
    }
}
