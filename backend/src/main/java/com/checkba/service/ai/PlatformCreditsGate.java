// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.LangText;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountRequired;
import com.checkba.service.account.AccountService;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Cloud spending requires a connected account and a confirmed positive balance. */
@Service
public class PlatformCreditsGate {
    private static final long FRESH_MS = 60_000L;
    private final AccountService accountService;
    private final PlatformAiChannel channel;
    private Long creditsCents;
    private long checkedAt;
    private String checkedOwner;

    public PlatformCreditsGate(AccountService accountService, PlatformAiChannel channel) {
        this.accountService = accountService;
        this.channel = channel;
    }

    public synchronized void ensureCredits(Long userId) {
        // Per-user server keys carry their own enforced quota; never query the machine account for them.
        if (!channel.usesMachineKey(userId)) return;
        String owner = accountService.accountFingerprintOrNull();
        if (owner == null) {
            throw AccountRequired.exception(AccountRequired.REASON_PLATFORM_AI,
                    LangText.of("请登录后使用云端 AI", "Sign in to use cloud AI"));
        }
        // Expired/unknown balances must be checked before spending, not refreshed after allowing a call.
        // Do not cache zero: a completed top-up must work on the next attempt.
        if (!owner.equals(checkedOwner) || creditsCents == null || creditsCents <= 0
                || System.currentTimeMillis() - checkedAt >= FRESH_MS) {
            reset();
            Map<String, Object> quota = accountService.fetchAiUsage();
            Object credits = quota.get("creditsCents");
            if (!(credits instanceof Number number)) {
                throw new AccountException(AccountException.Kind.NETWORK,
                        LangText.of("暂时无法确认账户余额，请稍后重试", "Unable to verify your balance. Please try again shortly"));
            }
            // A concurrent account switch must not reuse the previous account's balance.
            if (!owner.equals(accountService.accountFingerprintOrNull())) {
                throw AccountRequired.exception(AccountRequired.REASON_PLATFORM_AI,
                        LangText.of("账户已切换，请重试", "The account changed. Please try again"));
            }
            creditsCents = number.longValue();
            checkedOwner = owner;
            checkedAt = System.currentTimeMillis();
        }
        if (creditsCents <= 0L) {
            throw new AccountException(AccountException.Kind.CONFLICT,
                    LangText.of("账户 Credits 余额不足，充值后即可继续使用", "Your Credits balance is insufficient. Top up to continue"),
                    "no_credits");
        }
    }

    public synchronized void reset() {
        creditsCents = null;
        checkedAt = 0L;
        checkedOwner = null;
    }
}
