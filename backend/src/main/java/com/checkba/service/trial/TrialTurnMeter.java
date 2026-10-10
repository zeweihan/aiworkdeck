// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 试用计量 v0.1.1 · 回合上报（规格 §1 / §2 / §4）。
 *
 * <p><b>本机不判定、不扣减</b>（总经理 2026-10-09 决定：权威在账户站）。这里只做三件事：
 * <ol>
 *   <li>决定一个回合<b>够不够格上报</b>：成功收尾 + 有可展示的助手正文 + 本轮确实走了云端平台通道。
 *       失败 / 取消 / 断流 / 纯本地回合根本不上报（规格 §1.4）。</li>
 *   <li>以 runId 作 {@code turnId} 幂等键 POST 给账户站（同一提交内的工具多跳只有一个 runId，规格 §1.5）。</li>
 *   <li>把账户站回的余额写进本地缓存表、作废 TTL 缓存，供界面展示。</li>
 * </ol>
 * 上报异步、失败只记日志：漏报的方向是「少计」，对用户有利，不阻断任何本地功能（规格 §4 末段）。
 */
@Service
public class TrialTurnMeter {

    private static final Logger log = LoggerFactory.getLogger(TrialTurnMeter.class);

    /** bubble_end 里代表「这一轮成功收尾」的 status。paused / awaiting_approval 不是终态完成。 */
    public static final java.util.Set<String> COMPLETED_STATUSES = java.util.Set.of("finished", "awaiting_input");

    private final AccountService accountService;
    private final TrialBalanceService trialBalanceService;
    private final Executor executor;
    private final Clock clock;

    @Autowired
    public TrialTurnMeter(AccountService accountService, TrialBalanceService trialBalanceService) {
        this(accountService, trialBalanceService, defaultExecutor(), Clock.systemUTC());
    }

    TrialTurnMeter(AccountService accountService, TrialBalanceService trialBalanceService,
                   Executor executor, Clock clock) {
        this.accountService = accountService;
        this.trialBalanceService = trialBalanceService;
        this.executor = executor;
        this.clock = clock;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trial-turn-meter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 回合收尾钩子。
     *
     * @param turnId             runId（一次用户提交 = 一个 runId）
     * @param userId             本机用户（只用于写本地缓存行）
     * @param bubbleStatus       bubble_end 的 status 字面量
     * @param hasAssistantResult 本轮是否已向用户交付过非空助手正文
     * @param platformUsed       本轮是否调用过云端平台通道
     * @return 是否已提交上报（测试用）
     */
    public boolean onTurnEnded(String turnId, Long userId, String bubbleStatus,
                               boolean hasAssistantResult, boolean platformUsed) {
        if (turnId == null || !COMPLETED_STATUSES.contains(bubbleStatus)) return false;
        if (!hasAssistantResult || !platformUsed) return false;
        if (!accountService.isConnected()) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("turnId", turnId);
        body.put("outcome", "completed");
        body.put("hasAssistantResult", true);
        body.put("endedAt", clock.instant().toString());
        executor.execute(() -> report(userId, body));
        return true;
    }

    private void report(Long userId, Map<String, Object> body) {
        try {
            Map<String, Object> reply = accountService.reportTrialTurn(body);
            accountService.clearTrialCache();
            if (userId != null && reply != null && reply.get("balance") instanceof Map<?, ?> balance) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) balance;
                trialBalanceService.cacheFromAccount(userId, b);
            }
        } catch (AccountException e) {
            log.info("试用回合上报未成功（不影响本地功能，账户站以自身记录为准）: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("试用回合上报异常", e);
        }
    }
}
