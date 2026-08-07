package com.checkba.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * per-user 平台 AI key 的落库加密（server 模式多租户）。
 *
 * <p>密文形态与官网仓 {@code lib/openrouter-keys.ts} <b>逐字对齐</b>：
 * {@code v1:<ivB64>:<tagB64>:<cipherB64>}，AES-256-GCM，加密密钥 = SHA-256(secret)。
 * 两侧同形态是刻意的——同一把 runtime key 在官网库与 server 库里长得一样，排查时不必换脑子。
 *
 * <p>GCM 的 tag 保证篡改即解密失败：DB 里被改过的密文不会解出一把「能用但不是你的」key。
 *
 * <p><b>启动强不变式</b>：{@code security.awdk-login-enabled=true}（官方托管的插件云后端形态）
 * 时 secret 必须配置，否则拒绝启动。明文兜底属于「潜伏逃生门」——真出事时没人记得这里曾经
 * 悄悄降级过。local-mode 与未开桥接的团队服务器不受影响：它们根本不走 per-user 路径。
 */
@Component
public class PlatformAiKeyCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public PlatformAiKeyCipher(
            @Value("${security.platform-key-secret:}") String secret,
            @Value("${security.awdk-login-enabled:false}") boolean awdkLoginEnabled) {
        String trimmed = secret == null ? "" : secret.trim();
        if (trimmed.isEmpty()) {
            if (awdkLoginEnabled) {
                throw new IllegalStateException(
                        "开启账户桥接（security.awdk-login-enabled=true）时必须配置 "
                                + "security.platform-key-secret（环境变量 AWD_PLATFORM_KEY_SECRET）："
                                + "该密钥用于加密每个用户的平台 AI 通道密钥，缺失时拒绝以明文降级运行。");
            }
            this.key = null;
            return;
        }
        this.key = sha256(trimmed);
    }

    /** 未配置 secret 时 per-user 存储整体不可用（调用方据此给业务错误，而不是明文降级）。 */
    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // JCE 把 tag 附在密文尾部，这里拆开以对齐官网的三段式
            int tagBytes = TAG_BITS / 8;
            byte[] body = new byte[out.length - tagBytes];
            byte[] tag = new byte[tagBytes];
            System.arraycopy(out, 0, body, 0, body.length);
            System.arraycopy(out, body.length, tag, 0, tagBytes);
            Base64.Encoder b64 = Base64.getEncoder();
            return String.join(":", VERSION, b64.encodeToString(iv), b64.encodeToString(tag), b64.encodeToString(body));
        } catch (Exception e) {
            // 异常 message 里绝不带明文（同 AccountService.stateMapper 的考虑）
            throw new IllegalStateException("平台 AI 通道密钥加密失败");
        }
    }

    /** 解密失败（密文损坏 / secret 换过 / 被篡改）一律抛错，调用方按「没有 key」降级。 */
    public String decrypt(String encoded) {
        requireConfigured();
        String[] parts = encoded == null ? new String[0] : encoded.split(":");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("平台 AI 通道密钥密文格式不支持");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] iv = b64.decode(parts[1]);
            byte[] tag = b64.decode(parts[2]);
            byte[] body = b64.decode(parts[3]);
            byte[] joined = new byte[body.length + tag.length];
            System.arraycopy(body, 0, joined, 0, body.length);
            System.arraycopy(tag, 0, joined, body.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("平台 AI 通道密钥解密失败");
        }
    }

    /** 模型实例缓存与对账 baseline 的分桶键。key 换了指纹就变。 */
    public static String fingerprint(String plaintextKey) {
        byte[] digest = sha256(plaintextKey);
        return java.util.HexFormat.of().formatHex(digest, 0, 6);
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("security.platform-key-secret 未配置");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
