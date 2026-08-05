package com.checkba.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 试用码离线验签契约锁定（与官网签发脚本 PR-W4 对齐）：
 * "AWD-T-" + RFC4648 大写 base32 无 padding（连字符忽略），
 * 解码 70 字节 = payload(6B: [0x01,0x01,iat_u32_BE]) + Ed25519 签名(64B)。
 */
class TrialCodeVerifierTest {

    /** README 公开的通用试用码（用内置公钥对应私钥真实签发）。 */
    private static final String VALID_CODE =
            "AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y"
                    + "-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK";

    private static PublicKey publicKey;

    @BeforeAll
    static void loadKey() throws Exception {
        try (var in = TrialCodeVerifierTest.class.getResourceAsStream("/license/trial-public-key.pem")) {
            assertNotNull(in, "内置试用码公钥资源应存在");
            publicKey = TrialCodeVerifier.parsePublicKeyPem(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void validCodePasses() {
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify(VALID_CODE, publicKey);
        assertTrue(result.valid(), "有效试用码应通过验签: " + result.error());
        assertNull(result.error());
        assertTrue(result.issuedAtEpochSeconds() > 0, "应解出签发时间戳");
    }

    @Test
    void lowercaseAndSpacingTolerated() {
        // 用户手抄可能小写/夹空格——大小写与分隔符不参与验签语义
        String sloppy = VALID_CODE.toLowerCase(Locale.ROOT).replace("-", " - ");
        assertTrue(TrialCodeVerifier.verify(sloppy, publicKey).valid());
    }

    @Test
    void tamperedSignatureRejected() {
        // 改最后一个 base32 字符 = 篡改签名末字节
        char last = VALID_CODE.charAt(VALID_CODE.length() - 1);
        String tampered = VALID_CODE.substring(0, VALID_CODE.length() - 1) + (last == 'A' ? 'B' : 'A');
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify(tampered, publicKey);
        assertFalse(result.valid());
        assertTrue(result.error().contains("签名"), "错误信息应指向签名: " + result.error());
    }

    @Test
    void tamperedPayloadRejected() {
        // 改前缀后第一组里的字符 = 篡改 payload，签名对不上
        int idx = "AWD-T-AE".length() - 1; // 第一组第二个字符 'E'
        String tampered = VALID_CODE.substring(0, idx)
                + (VALID_CODE.charAt(idx) == 'F' ? 'G' : 'F')
                + VALID_CODE.substring(idx + 1);
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify(tampered, publicKey);
        assertFalse(result.valid());
    }

    @Test
    void badFormatRejected() {
        assertFalse(TrialCodeVerifier.verify(null, publicKey).valid());
        assertFalse(TrialCodeVerifier.verify("", publicKey).valid());
        assertFalse(TrialCodeVerifier.verify("HELLO-WORLD", publicKey).valid());
        // 前缀对但含非 base32 字符（0、1、8、9 不在 RFC4648 字母表里）
        assertFalse(TrialCodeVerifier.verify("AWD-T-0189-0189", publicKey).valid());
        // awdk_ 账户 Key 不该走试用码验签通过
        assertFalse(TrialCodeVerifier.verify("awdk_abcdef", publicKey).valid());
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify("HELLO", publicKey);
        assertTrue(result.error().contains("格式"), "错误信息应指向格式: " + result.error());
    }

    @Test
    void truncatedCodeRejected() {
        // 截断一组（8 字符）→ 解码不足 70 字节
        String truncated = VALID_CODE.substring(0, VALID_CODE.length() - 9);
        TrialCodeVerifier.Result result = TrialCodeVerifier.verify(truncated, publicKey);
        assertFalse(result.valid());
        assertTrue(result.error().contains("格式"), "截断应按格式错误处理: " + result.error());
    }
}
