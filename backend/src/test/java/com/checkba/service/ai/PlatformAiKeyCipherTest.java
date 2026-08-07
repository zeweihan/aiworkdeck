package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * per-user 平台密钥的落库加密。密文形态与官网仓 lib/openrouter-keys.ts 对齐
 * （v1:iv:tag:cipher，AES-256-GCM），GCM tag 保证篡改即解密失败。
 */
class PlatformAiKeyCipherTest {

    private static final String KEY = "sk-or-v1-0123456789abcdef";

    private PlatformAiKeyCipher cipher(String secret) {
        return new PlatformAiKeyCipher(secret, false);
    }

    @Test
    @DisplayName("加解密往返")
    void roundTrip() {
        PlatformAiKeyCipher cipher = cipher("s3cr3t");
        String encoded = cipher.encrypt(KEY);
        assertNotEquals(KEY, encoded);
        assertFalse(encoded.contains(KEY), "密文里不得出现明文");
        assertEquals(KEY, cipher.decrypt(encoded));
    }

    @Test
    @DisplayName("密文形态：四段 v1:iv:tag:cipher（与官网侧同形态，两边排查不必换脑子）")
    void cipherTextShape() {
        String[] parts = cipher("s3cr3t").encrypt(KEY).split(":");
        assertEquals(4, parts.length);
        assertEquals("v1", parts[0]);
    }

    @Test
    @DisplayName("同一明文两次加密的密文不同（IV 随机），但都能解回来")
    void randomIv() {
        PlatformAiKeyCipher cipher = cipher("s3cr3t");
        String a = cipher.encrypt(KEY);
        String b = cipher.encrypt(KEY);
        assertNotEquals(a, b);
        assertEquals(KEY, cipher.decrypt(a));
        assertEquals(KEY, cipher.decrypt(b));
    }

    @Test
    @DisplayName("密文被篡改：解密必失败，绝不解出一把「能用但不是你的」key")
    void tamperedCipherTextFails() {
        PlatformAiKeyCipher cipher = cipher("s3cr3t");
        String encoded = cipher.encrypt(KEY);
        String[] parts = encoded.split(":");
        // 改掉密文体的最后一个字符
        String body = parts[3];
        char last = body.charAt(body.length() - 1);
        parts[3] = body.substring(0, body.length() - 1) + (last == 'A' ? 'B' : 'A');
        String tampered = String.join(":", parts);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("换了 secret：旧密文解不开（不会静默返回垃圾）")
    void secretRotationInvalidatesOldCipherText() {
        String encoded = cipher("old-secret").encrypt(KEY);
        assertThrows(IllegalStateException.class, () -> cipher("new-secret").decrypt(encoded));
    }

    @Test
    @DisplayName("格式不认识：明确抛错")
    void unsupportedFormat() {
        PlatformAiKeyCipher cipher = cipher("s3cr3t");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("v2:a:b:c"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("nonsense"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(null));
    }

    @Test
    @DisplayName("未配置 secret：整块不可用，而不是明文降级")
    void unconfiguredRefusesToWork() {
        PlatformAiKeyCipher cipher = cipher("");
        assertFalse(cipher.isConfigured());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt(KEY));
    }

    @Test
    @DisplayName("启动强不变式：开启账户桥接却没配 secret 直接拒绝启动（不留明文逃生门）")
    void bridgeWithoutSecretRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PlatformAiKeyCipher("  ", true));
        assertTrue(e.getMessage().contains("AWD_PLATFORM_KEY_SECRET"), e.getMessage());
    }

    @Test
    @DisplayName("未开桥接（local-mode / 团队服务器）：缺 secret 照常启动，走机器级路径")
    void nonBridgeDeploymentStartsWithoutSecret() {
        assertDoesNotThrow(() -> new PlatformAiKeyCipher(null, false));
    }

    @Test
    @DisplayName("指纹随 key 变化，长度稳定")
    void fingerprintChangesWithKey() {
        String a = PlatformAiKeyCipher.fingerprint(KEY);
        String b = PlatformAiKeyCipher.fingerprint(KEY + "x");
        assertEquals(12, a.length());
        assertNotEquals(a, b);
        assertEquals(a, PlatformAiKeyCipher.fingerprint(KEY));
    }
}
