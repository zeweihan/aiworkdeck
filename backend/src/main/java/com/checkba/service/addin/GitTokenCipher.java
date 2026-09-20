// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

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
 * 关联 git 仓库的访问令牌的落库加密（dev-board#720）。
 *
 * <p>算法与 {@link com.checkba.service.ai.PlatformAiKeyCipher} 同款——AES-256-GCM、
 * SHA-256 派生密钥、每次随机 12 字节 IV，密文是 {@code Base64(iv‖密文‖tag)}。
 * <b>密钥来源独立</b>（{@code addin.git.token-secret}，环境变量 AWD_GIT_TOKEN_SECRET）：
 * 平台 AI 通道密钥与用户的 git 令牌是两类凭据，泄一把不该带走另一把。
 *
 * <p>GCM 的 tag 保证篡改即解密失败：库里被改过的密文不会解出一把「能用但不是你的」令牌。
 *
 * <p>没配密钥时整块不可用（{@link #enabled()} 为假），调用方据此给出「服务器未配置 git 令牌密钥」
 * 的业务错误——<b>不明文降级</b>。这里不像 PlatformAiKeyCipher 那样拒绝启动：git 关联是可选功能，
 * 不配就是不提供，没必要把整个服务拖下水。
 *
 * <p>红线：异常 message 里绝不带明文令牌（它会进日志）。
 */
@Component
public class GitTokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public GitTokenCipher(@Value("${addin.git.token-secret:}") String secret) {
        String trimmed = secret == null ? "" : secret.trim();
        this.key = trimmed.isEmpty() ? null : sha256(trimmed);
    }

    /** 本服务器是否配了 git 令牌密钥；为假时不接受带令牌的关联。 */
    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // message 与 cause 都不带明文：它们会进日志
            throw new IllegalStateException("git 访问令牌加密失败");
        }
    }

    /** 解密失败（密文损坏 / 密钥换过 / 被篡改）一律抛错，调用方按「这条关联用不了」处理。 */
    public String decrypt(String encoded) {
        requireEnabled();
        try {
            byte[] raw = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("too short");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] body = new byte[raw.length - IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            System.arraycopy(raw, IV_BYTES, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("git 访问令牌解密失败");
        }
    }

    private void requireEnabled() {
        if (key == null) {
            throw new IllegalStateException("addin.git.token-secret 未配置");
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
