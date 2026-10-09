// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.trial.TrialBalanceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * GET /api/trial/balance —— 试用计量 v0.1.1 spike：只读试用余额（服务端权威）。
 *
 * <p>14 天或 80 次 AI 调用，先到为准。本端点不发放、不扣次；客户端乐观展示以此纠偏。
 * 鉴权同全站：{@link AuthController#getUserIdFromSession}（local-mode 解析为本机用户）。
 * 返回沿用 {@code {code:0,data:...}} 信封。
 */
@RestController
public class TrialController {

    private final TrialBalanceService trialBalanceService;

    public TrialController(TrialBalanceService trialBalanceService) {
        this.trialBalanceService = trialBalanceService;
    }

    @GetMapping("/api/trial/balance")
    public ResponseEntity<Map<String, Object>> balance(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", 1);
            result.put("message", LangText.of("请先登录", "Please sign in first"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        result.put("code", 0);
        result.put("data", trialBalanceService.balance(userId));
        return ResponseEntity.ok(result);
    }
}
