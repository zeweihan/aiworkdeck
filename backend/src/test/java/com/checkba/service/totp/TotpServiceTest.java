package com.checkba.service.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    /** RFC 6238 Appendix B 的 SHA1 种子。 */
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("RFC 6238 官方测试向量（SHA1，8 位）逐条对拍")
    void matchesRfc6238Vectors() {
        // 表来自 RFC 6238 Appendix B：{Unix 时间秒, 期望 8 位码}
        long[][] vectors = {
                {59L, 94287082L},
                {1111111109L, 7081804L},
                {1111111111L, 14050471L},
                {1234567890L, 89005924L},
                {2000000000L, 69279037L},
                {20000000000L, 65353130L},
        };
        for (long[] v : vectors) {
            String expected = String.format("%08d", v[1]);
            String actual = TotpService.code(RFC_SEED, v[0] / 30, 8);
            assertEquals(expected, actual, "T=" + v[0]);
        }
    }

    @Test
    @DisplayName("base32 编解码往返；解码容忍空白/连字符/小写")
    void base32RoundTrip() {
        byte[] raw = {0, 1, 2, (byte) 0xff, 42, (byte) 0x80, 7, 63, (byte) 0xa5, 17};
        String encoded = TotpService.base32Encode(raw);
        assertArrayEquals(raw, TotpService.base32Decode(encoded));
        assertArrayEquals(raw, TotpService.base32Decode(
                encoded.toLowerCase().replaceAll("(.{4})", "$1 ")));
        assertThrows(IllegalArgumentException.class, () -> TotpService.base32Decode("ABC1!"));
    }

    @Test
    @DisplayName("当前时间片的码通过，并返回时间片序号供重放拦截")
    void verifiesCurrentCode() {
        AtomicLong clock = new AtomicLong(1_700_000_000L);
        TotpService svc = new TotpService(clock::get);
        String secret = svc.newSecret();
        long step = clock.get() / 30;
        String code = TotpService.code(TotpService.base32Decode(secret), step, 6);

        assertEquals(step, svc.verify(secret, code));
    }

    @Test
    @DisplayName("时钟漂移前后各一个步长容忍，第二个步长外拒绝")
    void toleratesOneStepSkew() {
        AtomicLong clock = new AtomicLong(1_700_000_000L);
        TotpService svc = new TotpService(clock::get);
        String secret = svc.newSecret();
        byte[] key = TotpService.base32Decode(secret);
        long step = clock.get() / 30;

        assertEquals(step - 1, svc.verify(secret, TotpService.code(key, step - 1, 6)));
        assertEquals(step + 1, svc.verify(secret, TotpService.code(key, step + 1, 6)));
        assertEquals(-1, svc.verify(secret, TotpService.code(key, step - 2, 6)));
        assertEquals(-1, svc.verify(secret, TotpService.code(key, step + 2, 6)));
    }

    @Test
    @DisplayName("错码/空码/位数不对/非数字/坏密钥一律 -1，不抛异常")
    void rejectsMalformedInput() {
        TotpService svc = new TotpService(() -> 1_700_000_000L);
        String secret = svc.newSecret();
        assertEquals(-1, svc.verify(secret, "000000".equals(currentCode(svc, secret)) ? "111111" : "000000"));
        assertEquals(-1, svc.verify(secret, null));
        assertEquals(-1, svc.verify(secret, ""));
        assertEquals(-1, svc.verify(secret, "12345"));
        assertEquals(-1, svc.verify(secret, "abcdef"));
        assertEquals(-1, svc.verify("not!base32", "123456"));
        assertEquals(-1, svc.verify(null, "123456"));
    }

    @Test
    @DisplayName("密钥每次不同且为 32 字符 base32（160 位）")
    void secretsAreRandomAndWellFormed() {
        TotpService svc = new TotpService();
        String a = svc.newSecret();
        String b = svc.newSecret();
        assertNotEquals(a, b);
        assertEquals(32, a.length());
        assertEquals(20, TotpService.base32Decode(a).length);
    }

    @Test
    @DisplayName("otpauth URI 含密钥/发行方/参数，且账号名里的空格与冒号被转义")
    void provisioningUriShape() {
        TotpService svc = new TotpService();
        String uri = svc.provisioningUri("ABC234", "zhang san", "AI WorkDeck");
        assertTrue(uri.startsWith("otpauth://totp/AI%20WorkDeck:zhang%20san?"), uri);
        assertTrue(uri.contains("secret=ABC234"));
        assertTrue(uri.contains("issuer=AI%20WorkDeck"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    private static String currentCode(TotpService svc, String secret) {
        return TotpService.code(TotpService.base32Decode(secret), 1_700_000_000L / 30, 6);
    }
}
