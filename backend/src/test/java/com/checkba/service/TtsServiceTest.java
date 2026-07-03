package com.checkba.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
