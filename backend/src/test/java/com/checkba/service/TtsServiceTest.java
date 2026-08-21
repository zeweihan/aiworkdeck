package com.checkba.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTS local provider（Phase 3）：rate → Kokoro speed 的容错解析。
 * 前端滑杆传 "1.2"/"1.2x" 一类字符串；越界与垃圾输入回退 1.0。
 */
class TtsServiceTest {

    @Test
    void parseSpeedAcceptsPlainAndSuffixedNumbers() {
        assertEquals(1.2, TtsService.parseSpeed("1.2"), 1e-9);
        assertEquals(1.2, TtsService.parseSpeed("1.2x"), 1e-9);
        assertEquals(0.5, TtsService.parseSpeed("0.5X"), 1e-9);
    }

    @Test
    void parseSpeedFallsBackToOneOnJunkOrOutOfRange() {
        assertEquals(1.0, TtsService.parseSpeed(null), 1e-9);
        assertEquals(1.0, TtsService.parseSpeed(""), 1e-9);
        assertEquals(1.0, TtsService.parseSpeed("fast"), 1e-9);
        assertEquals(1.0, TtsService.parseSpeed("3.5"), 1e-9);
        assertEquals(1.0, TtsService.parseSpeed("0.1"), 1e-9);
    }

    /**
     * 回归：前端曾把 {@code rate} 写死成百分比形式的 "+0%"，本方法解析失败恒回落 1.0——
     * 结果是语速滑杆调了也没有任何效果，而且从日志上看不出异常。
     * 这条不是要求支持百分比格式，恰恰相反：它钉住「百分比串是无效输入」这个事实，
     * 谁再往这里传百分比，就得先想清楚倍率该怎么算。
     */
    @Test
    void percentStyleRateIsInertNotSilentlyConverted() {
        assertEquals(1.0, TtsService.parseSpeed("+0%"), 1e-9);
        assertEquals(1.0, TtsService.parseSpeed("+30%"), 1e-9);
        // 前端现在发的是倍率串，这才是会真正生效的那一种
        assertEquals(1.3, TtsService.parseSpeed("1.3"), 1e-9);
    }

    /**
     * 读超时不是「组件没装」。RestTemplate 把连接被拒与读超时裹成同一个
     * ResourceAccessException，此前一律报成「请去组件管理下载语音组件」——
     * 组件明明在跑，用户被支去重装一个和故障无关的东西。
     */
    @Test
    void readTimeoutIsNotMistakenForMissingComponent() {
        org.springframework.web.client.ResourceAccessException readTimeout =
                new org.springframework.web.client.ResourceAccessException(
                        "I/O error", new java.net.SocketTimeoutException("Read timed out"));
        assertFalse(TtsService.isServiceUnreachable(readTimeout), "读超时说明服务在跑，不该判成未就绪");

        org.springframework.web.client.ResourceAccessException refused =
                new org.springframework.web.client.ResourceAccessException(
                        "I/O error", new java.net.ConnectException("Connection refused"));
        assertTrue(TtsService.isServiceUnreachable(refused), "连接被拒才是组件没起");

        // 认不出来的原因保持旧行为
        assertTrue(TtsService.isServiceUnreachable(
                new org.springframework.web.client.ResourceAccessException(
                        "I/O error", new java.io.IOException("broken pipe"))));
    }
}
