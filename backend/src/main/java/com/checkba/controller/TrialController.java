// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.account.AccountService;
import com.checkba.service.trial.TrialBalanceService;
import com.checkba.service.trial.TrialPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/trial/balance —— 试用计量 v0.1.1：试用余额（14 天或 80 次 AI 回合，先到为准）。
 *
 * <p><b>权威在账户站</b>：数据源 {@link AccountService#trialSnapshot()}，代理方式与
 * {@code GET /api/account/balance}（{@link AccountService#balanceSnapshot()}）同一套：
 * Bearer awdk_、TTL 缓存按账户指纹隔离、官网不可达降级 {@code available:false}。
 * 本机 {@link TrialBalanceService} 只做缓存：账户站回包覆盖本地行；官网不可达时回缓存并标
 * {@code authoritative:false}。未连接账户 {@code {connected:false}}，前端不展示试用条。
 */
@RestController
public class TrialController {

    private final TrialBalanceService trialBalanceService;
    private final AccountService accountService;

    public TrialController(TrialBalanceService trialBalanceService, AccountService accountService) {
        this.trialBalanceService = trialBalanceService;
        this.accountService = accountService;
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
        result.put("data", snapshot(userId));
        return ResponseEntity.ok(result);
    }

    Map<String, Object> snapshot(Long userId) {
        Map<String, Object> snap = accountService.trialSnapshot();
        Map<String, Object> data = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(snap.get("connected"))) {
            data.put("connected", false);
            data.put("termsNotice", TrialPolicy.TERMS_NOTICE);
            return data;
        }
        if (!Boolean.TRUE.equals(snap.get("available")) || !(snap.get("trial") instanceof Map<?, ?>)) {
            // 官网不可达：回本地缓存做展示兜底，明确标非权威
            data.putAll(trialBalanceService.balance(userId));
            data.put("connected", true);
            data.put("available", false);
            data.put("stale", true);
            return data;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> trial = (Map<String, Object>) snap.get("trial");
        try {
            trialBalanceService.cacheFromAccount(userId, trial);
        } catch (RuntimeException ignore) {
            // 缓存写失败不影响展示权威值
        }
        data.putAll(trial);
        data.put("connected", true);
        data.put("available", true);
        data.put("authoritative", true);
        data.put("source", "account");
        data.put("termsNotice", TrialPolicy.TERMS_NOTICE);
        return data;
    }
}
