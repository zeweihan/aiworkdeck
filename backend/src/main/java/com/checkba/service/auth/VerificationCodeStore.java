package com.checkba.service.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 验证码的签发与核销，**短信与邮件共用一套生命周期**（进程内内存，与 AuthAbuseGuard
 * 同一实现边界：单实例基线，多实例部署需外置存储；验证码登录仅在官方托管的插件云后端
 * 启用，当前恒单实例）。
 *
 * <p>target 是「发给谁」的标识：短信是规范化手机号，邮件是规范化邮箱地址。两者不会撞键——
 * 手机号里没有 {@code @}。共用一套的好处是防轰炸、爆破上限、一次性语义只有一份实现，
 * 不必为新增通道再维护一条平行的验证码路径。
 *
 * <ul>
 *   <li>验证码 6 位数字，{@link #TTL 5 分钟}有效，验证成功即销毁（一次性）。</li>
 *   <li>同一 scene+target {@link #RESEND_COOLDOWN 60 秒}内不可重发。</li>
 *   <li>连续 {@link #MAX_ATTEMPTS} 次验错即作废（防在线爆破 6 位码）。</li>
 *   <li>单 target 每天最多 {@link #MAX_PER_TARGET_PER_DAY} 条（防轰炸的最后一道；IP 维度在 AuthAbuseGuard）。</li>
 *   <li>内存只存 SHA-256，不存明文码。</li>
 * </ul>
 */
@Service
public class VerificationCodeStore {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_PER_TARGET_PER_DAY = 10;
    private static final Duration DAY_WINDOW = Duration.ofHours(24);
    private static final int PURGE_THRESHOLD = 10_000;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LongSupplier nowMillis;
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> dailySends = new ConcurrentHashMap<>();

    public VerificationCodeStore() {
        this(System::currentTimeMillis);
    }

    /** 测试用：可控时钟。 */
    VerificationCodeStore(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /**
     * 签发一枚验证码（冷却期内 / 当日超量则抛业务错误）。
     * 调用方拿到返回值后负责真正把码发出去（短信或邮件）；发送失败时应调 {@link #invalidate}
     * 回收，否则冷却期会挡住用户的立即重试。
     */
    public String issue(String scene, String target) {
        // 「查冷却/查日上限」与「写新码/记条数」必须在同一临界区里：拆开就是 check-then-act，
        // 两个并发请求会一起看到「没码/没超」，于是同一秒发出两条码、日上限被一起越过。
        // 借 ConcurrentHashMap.compute 对同一 key 的原子性做这道锁；lambda 里抛异常时
        // 映射保持不变（冷却/超限被拒不会留下半条记录）。
        String[] issued = new String[1];
        codes.compute(key(scene, target), (k, existing) -> {
            long now = nowMillis.getAsLong();
            if (existing != null && now - existing.issuedAt < RESEND_COOLDOWN.toMillis()) {
                throw new IllegalArgumentException("验证码发送过于频繁，请稍后再试");
            }
            // 嵌套 compute 的是另一张表（dailySends），CHM 只禁止在 compute 里改「本表」；
            // 全局只有这一处按 codes -> dailySends 的顺序取锁，不存在反向路径。
            dailySends.compute(target, (t, c) -> {
                if (c == null || now - c.windowStart > DAY_WINDOW.toMillis()) {
                    c = new WindowCounter();
                    c.windowStart = now;
                }
                if (c.count >= MAX_PER_TARGET_PER_DAY) {
                    throw new IllegalArgumentException("该账号今日验证码条数已达上限，请明天再试");
                }
                c.count++;
                return c;
            });
            String code = String.valueOf(100000 + SECURE_RANDOM.nextInt(900000));
            issued[0] = code;
            Entry entry = new Entry();
            entry.codeHash = sha256(code);
            entry.issuedAt = now;
            entry.expiresAt = now + TTL.toMillis();
            return entry;
        });
        // 清理挪到临界区外：purge 会遍历改 codes 本表，放进 compute 里是 CHM 明令禁止的。
        purgeIfOversized(nowMillis.getAsLong());
        return issued[0];
    }

    /** 验证并核销：成功即销毁；连续验错超限作废。过期/不存在/错码一律 false。 */
    public boolean verify(String scene, String target, String code) {
        if (code == null || code.isBlank()) return false;
        // 哈希放在临界区外算，锁只盖住「读计数-加一-写回/作废」。
        byte[] guess = sha256(code.trim());
        // 此前 ++entry.attempts 是裸的字段自增：并发验错会丢更新，5 次上限挡不住
        // 真打过来的 5 次以上猜测。compute 对同一 key 原子，计数不会漏记。
        boolean[] ok = new boolean[1];
        codes.compute(key(scene, target), (k, entry) -> {
            long now = nowMillis.getAsLong();
            if (entry == null || now > entry.expiresAt) {
                return null; // 过期/不存在：顺手清掉（返回 null 即移除映射）
            }
            if (!constantTimeEquals(entry.codeHash, guess)) {
                entry.attempts++;
                return entry.attempts >= MAX_ATTEMPTS ? null : entry;
            }
            ok[0] = true;
            return null; // 一次性：成功即核销
        });
        return ok[0];
    }

    /** 回收一枚未消费的码（发送失败时回滚冷却，不回滚当日计数——网关受理即可能计费）。 */
    public void invalidate(String scene, String target) {
        codes.remove(key(scene, target));
    }

    /** 测试用：当前累计验错次数（不存在返回 -1）。 */
    int attemptsForTest(String scene, String target) {
        Entry e = codes.get(key(scene, target));
        return e == null ? -1 : e.attempts;
    }

    /** 测试用：当日已签发条数。 */
    int dailySendsForTest(String target) {
        WindowCounter c = dailySends.get(target);
        return c == null ? 0 : c.count;
    }

    private static String key(String scene, String target) {
        return scene + "|" + target;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private void purgeIfOversized(long now) {
        if (codes.size() > PURGE_THRESHOLD) {
            codes.entrySet().removeIf(e -> now > e.getValue().expiresAt);
        }
        if (dailySends.size() > PURGE_THRESHOLD) {
            dailySends.entrySet().removeIf(e -> now - e.getValue().windowStart > DAY_WINDOW.toMillis());
        }
    }

    private static final class Entry {
        byte[] codeHash;
        long issuedAt;
        long expiresAt;
        int attempts;
    }

    private static final class WindowCounter {
        int count;
        long windowStart;
    }
}
