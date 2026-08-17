package com.checkba.service.meeting;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

/**
 * 录音转码：MediaRecorder 产出的 webm/opus 转成 16kHz 单声道 mp3 再送听悟。
 * 两个理由：mp3 是听悟明确支持的格式（webm 容器未在支持清单里），
 * 且 48kbps 单声道把上传体积压到 wav 的二十分之一。
 * 转码失败（编码器缺失/容器损坏）退回原文件——听悟对常见容器有一定容错，宁可试一次也别直接失败。
 */
@Slf4j
@Component
public class MeetingAudioTranscoder {

    private static final int SAMPLE_RATE = 16000;
    /** 包内可见：平台档在前端没回报时长时按这个码率从产物体积反推时长。 */
    static final int BITRATE = 48_000;

    /** 转成 mp3；失败时返回原文件（调用方按返回文件的扩展名决定 OSS objectKey）。 */
    public File toMp3(File input, Path workDir) {
        File output = workDir.resolve("meeting-audio.mp3").toFile();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input)) {
            grabber.start();
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 1)) {
                recorder.setFormat("mp3");
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
                recorder.setSampleRate(SAMPLE_RATE);
                recorder.setAudioBitrate(BITRATE);
                recorder.setAudioChannels(1);
                recorder.start();
                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    recorder.record(frame);
                }
                recorder.stop();
            }
            grabber.stop();
            if (output.length() > 0) {
                return output;
            }
            log.warn("转码产物为空，回退原始音频: {}", input.getName());
            return input;
        } catch (Throwable t) {
            // Throwable：bytedeco 原生库缺失时抛 UnsatisfiedLinkError，不能让它穿透
            log.warn("音频转码失败，回退原始音频: {}", t.toString());
            return input;
        }
    }
}
