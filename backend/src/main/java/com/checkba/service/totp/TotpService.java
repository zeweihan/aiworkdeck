package com.checkba.service.totp;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * TOTP（RFC 6238）验证器：认证器 App 二次验证的纯算法层。
 *
 * <p>不引依赖：算法就是 HMAC-SHA1 + 动态截断，几十行覆盖，且有 RFC 6238 官方测试向量
 * 作护栏（{@code TotpServiceTest}）。同 {@code SmsService} 的立场——保增量更新补丁通道资格。
 *
 * <p>为什么给国际用户优先用 TOTP 而不是短信：零边际成本、无运营商报备、无国界、离线可用，
 * 且安全性高于短信（不受 SIM 交换攻击）。短信只作为大陆用户的习惯路径与兜底。
 *
 * <p>时间漂移容忍 {@link #SKEW_STEPS} 个步长（前后各 30 秒）。**不做重放拦截**——
 * 上层 {@code SecondFactorService} 记录已消费的时间片，同一码不可用两次。
 */
@Service
public class TotpService {

    static final Duration STEP = Duration.ofSeconds(30);
    static final int DIGITS = 6;
    /** 前后各容忍 1 个步长：用户手机与服务器时钟通常差不到 30 秒。 */
    static final int SKEW_STEPS = 1;
    /** 160 位密钥，与 HMAC-SHA1 块长匹配，也是认证器 App 的通行规格。 */
    private static final int SECRET_BYTES = 20;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final LongSupplier nowSeconds;

    public TotpService() {
        this(() -> System.currentTimeMillis() / 1000);
    }

    /** 测试用：可控时钟。 */
    TotpService(LongSupplier nowSeconds) {
        this.nowSeconds = nowSeconds;
    }

    /** 生成新密钥（base32，无 padding，认证器 App 的手工录入格式）。 */
    public String newSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * 认证器 App 的配置 URI（otpauth://），供二维码或手工录入。
     *
     * @param account 显示在 App 里的账号名（一般是用户名）
     * @param issuer  显示在 App 里的服务名
     */
    public String provisioningUri(String secret, String account, String issuer) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + STEP.toSeconds();
    }

    /**
     * 校验验证码，返回命中的时间片序号（供上层做重放拦截）；不匹配返回 -1。
     * 时间片序号单调递增，上层只需拒绝「不大于上次已用序号」的码。
     */
    public long verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) return -1;
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS || !trimmed.chars().allMatch(Character::isDigit)) return -1;
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        long current = nowSeconds.getAsLong() / STEP.toSeconds();
        for (long step = current - SKEW_STEPS; step <= current + SKEW_STEPS; step++) {
            if (constantTimeEquals(code(key, step, DIGITS), trimmed)) {
                return step;
            }
        }
        return -1;
    }

    /** RFC 4226 HOTP：HMAC-SHA1 + 动态截断。包级可见供测试跑 RFC 6238 官方向量。 */
    static String code(byte[] key, long counter, int digits) {
        byte[] buffer = new byte[8];
        for (int i = 7; i >= 0; i--) {
            buffer[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(buffer);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int modulo = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", binary % modulo);
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String clean = encoded.replaceAll("[\\s=-]", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("非法 base32 字符: " + c);
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
