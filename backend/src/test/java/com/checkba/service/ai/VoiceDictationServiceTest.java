package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 语音听写守卫层（dev-board#153）：格式/时长/大小/通道可用性四道闸都在出网之前，
 * 任何一道不过都不该产生上游调用（也就不该扣用户的钱）。
 * 上游 schema（input_audio wav → 转写文本）已于 2026-08-25 用真凭证实测
 * （mimo-v2.5 / voxtral 普通话逐字准确），单测只钉不出网的部分。
 */
class VoiceDictationServiceTest {

    private VoiceDictationService service(PlatformAiChannel channel) {
        return new VoiceDictationService(channel, "https://unreachable.invalid/api/v1", "xiaomi/mimo-v2.5");
    }

    @Test
    @DisplayName("格式白名单：非 wav/mp3 直接拒，不碰通道")
    void rejectsBadFormat() {
        PlatformAiChannel channel = mock(PlatformAiChannel.class);
        var e = assertThrows(IllegalArgumentException.class,
                () -> service(channel).transcribe(1L, Base64.getEncoder().encodeToString(new byte[16]), "webm", 1000));
        assertTrue(e.getMessage().contains("wav"));
        verify(channel, never()).resolveFor(any());
    }

    @Test
    @DisplayName("时长闸：超 90 秒拒收")
    void rejectsTooLong() {
        PlatformAiChannel channel = mock(PlatformAiChannel.class);
        assertThrows(IllegalArgumentException.class,
                () -> service(channel).transcribe(1L, Base64.getEncoder().encodeToString(new byte[16]), "wav", 91_000));
        verify(channel, never()).resolveFor(any());
    }

    @Test
    @DisplayName("大小闸：超 6MB 拒收；空音频拒收")
    void rejectsOversizeAndEmpty() {
        PlatformAiChannel channel = mock(PlatformAiChannel.class);
        String big = Base64.getEncoder().encodeToString(new byte[6 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> service(channel).transcribe(1L, big, "wav", 1000));
        assertThrows(IllegalArgumentException.class, () -> service(channel).transcribe(1L, "", "wav", 1000));
        verify(channel, never()).resolveFor(any());
    }

    @Test
    @DisplayName("通道不可用（未桥接/BYOK）：明确报「连接账户」，不出网")
    void rejectsWhenChannelUnavailable() {
        PlatformAiChannel channel = mock(PlatformAiChannel.class);
        when(channel.resolveFor(1L)).thenReturn(null);
        var e = assertThrows(IllegalStateException.class,
                () -> service(channel).transcribe(1L, Base64.getEncoder().encodeToString(new byte[16]), "wav", 1000));
        assertTrue(e.getMessage().contains("账户") || e.getMessage().contains("account"));
    }
}
