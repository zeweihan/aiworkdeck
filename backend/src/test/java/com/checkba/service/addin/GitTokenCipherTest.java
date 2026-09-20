// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * git 访问令牌的落库加密（dev-board#720）。
 *
 * <p>要守住的四条：
 * <ol>
 *   <li>同一个明文两次加密的密文必须不同（随机 IV）——否则库里一眼能看出两个人用的是同一把令牌；</li>
 *   <li>密钥换过、密文被改过一律解不开（GCM 的 tag），不能解出一把「能用但不是你的」令牌；</li>
 *   <li>没配密钥时整块不可用，<b>不明文降级</b>（同 PlatformAiKeyCipher 的理由：
 *       悄悄降级过的地方，真出事时没人记得）；</li>
 *   <li>异常 message 里绝不带明文令牌——它会进日志。</li>
 * </ol>
 */
class GitTokenCipherTest {

    @Test
    void roundTripAndDistinctCiphertexts() {
        GitTokenCipher c = new GitTokenCipher("s3cret");
        String a = c.encrypt("ghp_abc");
        String b = c.encrypt("ghp_abc");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("ghp_abc");
        assertThat(c.decrypt(b)).isEqualTo("ghp_abc");
    }

    @Test
    void ciphertextNeverContainsPlaintext() {
        String cipherText = new GitTokenCipher("s3cret").encrypt("ghp_abc");
        assertThat(cipherText).doesNotContain("ghp_abc");
    }

    @Test
    void disabledWithoutSecret() {
        assertThat(new GitTokenCipher("").enabled()).isFalse();
        assertThat(new GitTokenCipher("   ").enabled()).isFalse();
        assertThat(new GitTokenCipher(null).enabled()).isFalse();
        assertThat(new GitTokenCipher("s3cret").enabled()).isTrue();
    }

    @Test
    void encryptWithoutSecretFailsLoudlyInsteadOfStoringPlaintext() {
        GitTokenCipher c = new GitTokenCipher("");
        assertThatThrownBy(() -> c.encrypt("ghp_abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.encrypt("ghp_abc")).hasMessageNotContaining("ghp_abc");
    }

    @Test
    void anotherSecretCannotDecrypt() {
        String cipherText = new GitTokenCipher("s3cret").encrypt("ghp_abc");
        assertThatThrownBy(() -> new GitTokenCipher("other").decrypt(cipherText))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        GitTokenCipher c = new GitTokenCipher("s3cret");
        byte[] raw = Base64.getDecoder().decode(c.encrypt("ghp_abc"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt("not-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(null)).isInstanceOf(IllegalStateException.class);
    }
}
