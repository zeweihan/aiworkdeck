package com.checkba.service;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * 试用码离线验签（与官网签发脚本 PR-W4 对齐的格式契约）。
 *
 * 码格式：`AWD-T-` + RFC4648 大写 base32（无 padding，连字符仅作分组、解析时忽略）。
 * 解码后恰为 70 字节 = payload(6B) + Ed25519 签名(64B，对 payload 签)：
 * payload = [0x01(版本), 0x01(类型 trial), iat_u32_BE(签发时间戳，秒)]
 * 公钥内置于 backend resources/license/trial-public-key.pem（与插件 registry 公钥独立密钥对）。
 */
public final class TrialCodeVerifier {

    /** 去掉连字符后码必须以此开头（"AWD" + "T"）。 */
    private static final String PREFIX = "AWDT";
    private static final int RAW_LENGTH = 70;
    private static final int PAYLOAD_LENGTH = 6;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TrialCodeVerifier() {
    }

    public record Result(boolean valid, String error, long issuedAtEpochSeconds) {
        static Result ok(long iat) {
            return new Result(true, null, iat);
        }

        static Result fail(String error) {
            return new Result(false, error, 0);
        }
    }

    public static Result verify(String code, PublicKey publicKey) {
        if (code == null || code.isBlank()) {
            return Result.fail("试用码格式不正确");
        }
        String normalized = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!normalized.startsWith(PREFIX)) {
            return Result.fail("试用码格式不正确");
        }
        byte[] raw;
        try {
            raw = base32Decode(normalized.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return Result.fail("试用码格式不正确");
        }
        if (raw.length != RAW_LENGTH) {
            return Result.fail("试用码格式不正确");
        }
        byte[] payload = Arrays.copyOfRange(raw, 0, PAYLOAD_LENGTH);
        byte[] signature = Arrays.copyOfRange(raw, PAYLOAD_LENGTH, RAW_LENGTH);
        if (payload[0] != 0x01 || payload[1] != 0x01) {
            return Result.fail("试用码格式不正确");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                return Result.fail("试用码签名无效");
            }
        } catch (Exception e) {
            return Result.fail("试用码签名无效");
        }
        long iat = ((payload[2] & 0xFFL) << 24)
                | ((payload[3] & 0xFFL) << 16)
                | ((payload[4] & 0xFFL) << 8)
                | (payload[5] & 0xFFL);
        return Result.ok(iat);
    }

    /** 解析 PEM 格式 Ed25519 公钥（X.509 SubjectPublicKeyInfo）。 */
    public static PublicKey parsePublicKeyPem(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 严格 RFC4648 大写 base32 解码（无 padding）。非法字符抛 IllegalArgumentException。 */
    private static byte[] base32Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = BASE32_ALPHABET.indexOf(s.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("非法 base32 字符: " + s.charAt(i));
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
