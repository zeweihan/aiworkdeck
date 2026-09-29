// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.meeting;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视频接入转写（dev-board#1024）的真实路径证据：{@link MeetingAudioTranscoder#toMp3} 拿到一个
 * 带 h264 画面 + aac 音轨的 mp4，必须抽出非空 mp3，而不是走「转码失败回退原文件」那条路——
 * 上层 {@code MeetingTranscriptionService.transcodeWithTimeout} 对视频只认 mp3 产物。
 * 其余转写测试全部 mock 了转码器，这里用真原生库跑一遍。夹具 tiny-video.mp4 由 ffmpeg 生成
 * （testsrc2 64x64 + 440Hz 正弦，1.2 秒，约 9KB）。
 */
class MeetingAudioTranscoderVideoTest {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("mp4 视频抽音轨得到非空 mp3，不回退原文件")
    void videoContainerYieldsMp3() throws Exception {
        File input = workDir.resolve("tiny-video.mp4").toFile();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/media/tiny-video.mp4")) {
            assertNotNull(in, "夹具 fixtures/media/tiny-video.mp4 缺失");
            Files.copy(in, input.toPath());
        }

        File out = new MeetingAudioTranscoder().toMp3(input, workDir);

        assertNotEquals(input.getAbsolutePath(), out.getAbsolutePath(), "转码器回退了原文件，视频抽音轨没有成功");
        assertTrue(out.getName().endsWith(".mp3"), "产物应为 mp3：" + out.getName());
        assertTrue(out.length() > 0, "mp3 产物为空");

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(out)) {
            grabber.start();
            assertEquals(1, grabber.getAudioChannels(), "应为单声道");
            assertEquals(16000, grabber.getSampleRate(), "应为 16kHz");
            assertTrue(grabber.getLengthInTime() > 800_000L, "时长应接近 1.2 秒，实际微秒=" + grabber.getLengthInTime());
            grabber.stop();
        }
    }
}
