// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import com.checkba.model.entity.TrialBalance;
import com.checkba.repository.TrialBalanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 试用计量 v0.1.1：试用余额的<b>本地缓存</b>（权威在账户站，见 {@code AccountService#trialSnapshot}）。
 *
 * <p>本地行只用于离线 / 官网不可达时的展示兜底；任何扣减、拦截都不读它。
 * {@link #cacheFromAccount} 是正式路径唯一的写入口（拿账户站回包覆盖）。
 *
 * <ul>
 *   <li>{@link #balance(Long)} —— GET /api/trial/balance 的数据源；纯读，不写库、不扣次。</li>
 *   <li>{@link #grant(Long, String)} —— 幂等发放（规格 §3.2 trial_grant）。本 spike <b>没有</b>
 *       HTTP 入口、也没有调用方；接线留给后续 PR（POST /api/trial/grant 或注册钩子）。</li>
 * </ul>
 *
 * 「先到为准」在读侧判定：到期看 {@code now >= trialEndsAt}，次数看 {@code callsUsed >= callsQuota}。
 * 两者同时满足时按落库状态优先（扣次路径落 exhausted_calls 时即为先到者），否则视为 expired_days。
 * 不接 ai_turn_ledger、不改 PlatformCreditsGate、不复用 TrialCodeVerifier。
 */
@Service
public class TrialBalanceService {

    private final TrialBalanceRepository repository;
    private final Clock clock;

    @Autowired
    public TrialBalanceService(TrialBalanceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    TrialBalanceService(TrialBalanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 只读余额快照。无行 = 未发放（status=none），按规格 §5 展示「剩余 14 天 / 80 次」。 */
    @Transactional(readOnly = true)
    public Map<String, Object> balance(Long userId) {
        Optional<TrialBalance> row = repository.findByUserId(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", TrialPolicy.POLICY_MIN_OF_DAYS_OR_CALLS);
        out.put("callsQuota", TrialPolicy.TRIAL_CALLS);
        out.put("daysQuota", TrialPolicy.TRIAL_DAYS);
        if (row.isEmpty()) {
            out.put("granted", false);
            out.put("status", TrialPolicy.STATUS_NONE);
            out.put("remainingDays", TrialPolicy.TRIAL_DAYS);
            out.put("remainingCalls", TrialPolicy.TRIAL_CALLS);
            out.put("callsUsed", 0);
            out.put("trialStartedAt", null);
            out.put("trialEndsAt", null);
            out.put("region", null);
        } else {
            TrialBalance b = row.get();
            Instant now = clock.instant();
            int quota = b.getCallsQuota() == null ? TrialPolicy.TRIAL_CALLS : b.getCallsQuota();
            int used = b.getCallsUsed() == null ? 0 : b.getCallsUsed();
            out.put("granted", true);
            out.put("status", effectiveStatus(b, now, quota, used));
            out.put("remainingDays", remainingDays(b.getTrialEndsAt(), now));
            out.put("remainingCalls", Math.max(0, quota - used));
            out.put("callsQuota", quota);
            out.put("callsUsed", used);
            out.put("trialStartedAt", b.getTrialStartedAt() == null ? null : b.getTrialStartedAt().toString());
            out.put("trialEndsAt", b.getTrialEndsAt() == null ? null : b.getTrialEndsAt().toString());
            out.put("region", b.getRegion());
        }
        out.put("serverTime", clock.instant().toString());
        // 本地行只是账户站余额的缓存（总经理 2026-10-09：权威在账户站），不得据此扣减或拦截
        out.put("authoritative", false);
        out.put("source", "local_cache");
        out.put("termsNotice", TrialPolicy.TERMS_NOTICE);
        return out;
    }

    /**
     * 幂等发放：已有行原样返回（不重置时钟、不加次数）。并发双发时依赖 user_id 唯一约束兜底，
     * 输的一方回读赢家那行。
     */
    @Transactional
    public TrialBalance grant(Long userId, String region) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        Optional<TrialBalance> existing = repository.findByUserId(userId);
        if (existing.isPresent()) return existing.get();
        Instant now = clock.instant();
        TrialBalance b = new TrialBalance();
        b.setUserId(userId);
        b.setTrialStartedAt(now);
        b.setTrialEndsAt(now.plus(TrialPolicy.TRIAL_DURATION));
        b.setCallsQuota(TrialPolicy.TRIAL_CALLS);
        b.setCallsUsed(0);
        b.setStatus(TrialPolicy.STATUS_ACTIVE);
        b.setPolicy(TrialPolicy.POLICY_MIN_OF_DAYS_OR_CALLS);
        b.setRegion(region);
        b.setCreatedAt(now);
        b.setUpdatedAt(now);
        try {
            return repository.saveAndFlush(b);
        } catch (DataIntegrityViolationException race) {
            return repository.findByUserId(userId).orElseThrow(() -> race);
        }
    }

    /**
     * 用账户站回包覆盖本地缓存行。账户站说「未发放」时不建行。
     * 字段名按账户站契约（camelCase）：trialStartedAt / trialEndsAt / callsQuota / callsUsed / status / policy / region。
     */
    @Transactional
    public void cacheFromAccount(Long userId, Map<String, Object> site) {
        if (userId == null || site == null) return;
        if (!Boolean.TRUE.equals(site.get("granted"))) return;
        Instant started = parseInstant(site.get("trialStartedAt"));
        Instant ends = parseInstant(site.get("trialEndsAt"));
        if (started == null || ends == null) return;
        Instant now = clock.instant();
        TrialBalance b = repository.findByUserId(userId).orElseGet(() -> {
            TrialBalance fresh = new TrialBalance();
            fresh.setUserId(userId);
            fresh.setCreatedAt(now);
            return fresh;
        });
        b.setTrialStartedAt(started);
        b.setTrialEndsAt(ends);
        b.setCallsQuota(intOr(site.get("callsQuota"), TrialPolicy.TRIAL_CALLS));
        b.setCallsUsed(intOr(site.get("callsUsed"), 0));
        Object status = site.get("status");
        b.setStatus(status instanceof String st && !st.isBlank() && !TrialPolicy.STATUS_NONE.equals(st)
                ? st : TrialPolicy.STATUS_ACTIVE);
        Object policy = site.get("policy");
        b.setPolicy(policy instanceof String p && !p.isBlank() ? p : TrialPolicy.POLICY_MIN_OF_DAYS_OR_CALLS);
        Object region = site.get("region");
        b.setRegion(region instanceof String r ? r : null);
        b.setUpdatedAt(now);
        try {
            repository.saveAndFlush(b);
        } catch (DataIntegrityViolationException race) {
            // 并发两次 GET 同时建行：输的一方放弃，缓存下一次再覆盖
        }
    }

    private static Instant parseInstant(Object v) {
        if (!(v instanceof String s) || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int intOr(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    static String effectiveStatus(TrialBalance b, Instant now, int quota, int used) {
        String stored = b.getStatus();
        if (TrialPolicy.STATUS_CONVERTED.equals(stored)) return stored;
        if (TrialPolicy.STATUS_EXHAUSTED_CALLS.equals(stored) || TrialPolicy.STATUS_EXPIRED_DAYS.equals(stored)) {
            return stored; // 已落终态者即为先到者
        }
        boolean expired = b.getTrialEndsAt() != null && !now.isBefore(b.getTrialEndsAt());
        if (expired) return TrialPolicy.STATUS_EXPIRED_DAYS;
        if (used >= quota) return TrialPolicy.STATUS_EXHAUSTED_CALLS;
        return TrialPolicy.STATUS_ACTIVE;
    }

    /** 剩余天数向上取整（剩 13 天 1 小时 → 14），到期为 0。 */
    static int remainingDays(Instant endsAt, Instant now) {
        if (endsAt == null || !now.isBefore(endsAt)) return 0;
        long secs = Duration.between(now, endsAt).getSeconds();
        long day = Duration.ofDays(1).getSeconds();
        return (int) ((secs + day - 1) / day);
    }
}
