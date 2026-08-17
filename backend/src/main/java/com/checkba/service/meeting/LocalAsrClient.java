package com.checkba.service.meeting;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 本机 asr-service（faster-whisper 的 OpenAI 兼容薄包装）的客户端。
 *
 * <p><b>本地档的全部出网就是零。</b>音频不离开本机，没有凭证、没有账单、没有对象存储中转。
 * 代价是没有说话人分离（faster-whisper 不提供；引 pyannote 要 HF token + 许可协议
 * + 额外几百 MB 模型，与「零配置」冲突），这一点必须在界面上写明。
 *
 * <p><b>探测的三态照 {@code OllamaProbeService} 的范式</b>，理由相同：本地档没有密钥可校验，
 * 只能靠探测判断能不能用；而「服务没起」与「模型没下」的下一步完全不同，
 * 合并成一个「不可用」等于让用户猜。
 */
@Slf4j
@Service
public class LocalAsrClient {

    /** 地址的 system_setting 覆盖键，形态与 {@code external.tts.localBaseUrl} 一致。 */
    public static final String SETTING_BASE_URL = "external.asr.localBaseUrl";

    /**
     * 探测超时。打的是回环地址，2 秒足够；长超时只会让「没装组件」这个最常见的分支干等，
     * 而这条探测挂在会议面板的装载路径上。
     */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 转写超时。<b>按整场会给，不能按一次 API 往返给</b>：medium 模型在纯 CPU 上
     * 大致是实时的三到八分之一，一场两小时的会常态要跑二十分钟到一小时，
     * 慢机器上更久。4 小时是「明显卡死了」的界，不是正常上限。
     */
    static final Duration TRANSCRIBE_TIMEOUT = Duration.ofHours(4);

    /** 探测结论三态，前端据此渲染「下一步该做什么」。 */
    public enum Status {
        /** 服务在跑且模型已下载 */
        READY,
        /** 服务在跑，但模型还没下载 */
        MODEL_MISSING,
        /** 连不上 / 响应异常（一律归到这一档） */
        SERVICE_DOWN
    }

    /**
     * @param diarization 说话人分离能力。本地档恒 false，读接口而不是在前端写死，
     *                    是为了将来换引擎时界面自动跟上
     */
    public record ProbeResult(
            Status status,
            String baseUrl,
            String model,
            boolean diarization,
            String message,
            String nextStep) {

        public boolean ready() {
            return status == Status.READY;
        }
    }

    private final SystemSettingService systemSettingService;
    private final String defaultBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 固定 HTTP/1.1：JDK HttpClient 默认 HTTP_2，对明文回环地址会先发 h2c 升级请求，
     * uvicorn 收到后不回字节，上层只看到一句「连不上」（HttpAccountTransport /
     * OllamaProbeService 都踩过同一个坑）。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public LocalAsrClient(SystemSettingService systemSettingService,
                          @Value("${external.asr.local-base-url:}") String defaultBaseUrl) {
        this.systemSettingService = systemSettingService;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
    }

    /** 打包态由 Electron 注入动态端口；dev 态给一个约定端口，省得每次改配置。 */
    public String baseUrl() {
        String value = systemSettingService.get(SETTING_BASE_URL, defaultBaseUrl);
        if (!StringUtils.hasText(value)) return "http://127.0.0.1:8890";
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 探一次。<b>永不抛异常</b>：对用户而言「连不上」「返回了看不懂的东西」是同一件事——
     * 本地转写现在用不了。
     */
    public ProbeResult probe() {
        String base = baseUrl();
        String body = getHealth(base);
        if (body == null) {
            return new ProbeResult(Status.SERVICE_DOWN, base, "", false,
                    LangText.of("本机转写服务没有运行。", "The on-device transcription service is not running."),
                    LangText.of("重启 AI Workdeck 让它自动拉起；仍不行时到「系统管理 - 组件管理」查看本机转写组件。",
                            "Restart AI Workdeck to bring it up; if it persists, check the on-device transcription component "
                                    + "under System settings - Components."));
        }
        try {
            JsonNode node = mapper.readTree(body);
            String model = node.path("model").asText("");
            boolean diarization = node.path("diarization").asBoolean(false);
            if (node.path("modelReady").asBoolean(false)) {
                return new ProbeResult(Status.READY, base, model, diarization,
                        LangText.of("本机转写已就绪，录音不会离开这台电脑。",
                                "On-device transcription is ready; recordings never leave this computer."),
                        LangText.of("本地转写没有说话人分离，速度也比云端慢（一场两小时的会通常要跑几十分钟）。",
                                "On-device transcription has no speaker separation and is slower than the cloud tier "
                                        + "(a two-hour meeting typically takes tens of minutes)."));
            }
            return new ProbeResult(Status.MODEL_MISSING, base, model, diarization,
                    LangText.of("本机转写服务已运行，但语音识别模型还没下载。",
                            "The on-device transcription service is running, but the speech model has not been downloaded."),
                    LangText.of("下载模型后即可让录音完全不出本机。",
                            "Download the model to keep recordings entirely on this computer."));
        } catch (Exception e) {
            log.debug("本机转写探测响应解析失败: {}", e.toString());
            return new ProbeResult(Status.SERVICE_DOWN, base, "", false,
                    LangText.of("本机转写服务返回了无法识别的响应。",
                            "The on-device transcription service returned an unrecognizable response."),
                    LangText.of("重启 AI Workdeck 后重新检测。", "Restart AI Workdeck and check again."));
        }
    }

    /**
     * 转写一份音频，返回 asr-service 的原始 JSON。
     *
     * <p>请求体<b>流式发送</b>（{@link SequenceInputStream} 串起首尾分隔符与文件流）：
     * 一场两小时的会几百 MB，先拼成 byte[] 会直接把桌面端后端撑爆。
     */
    public String transcribe(File audio) throws Exception {
        String boundary = "----awdasr" + Long.toHexString(System.nanoTime());
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + audio.getName() + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/audio/transcriptions"))
                        .timeout(TRANSCRIBE_TIMEOUT)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> multipartStream(head, audio, tail)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String detail = resp.body() == null ? "" : resp.body();
            throw new IllegalStateException("本机转写服务返回 " + resp.statusCode() + ": "
                    + detail.substring(0, Math.min(200, detail.length())));
        }
        return resp.body();
    }

    private static InputStream multipartStream(String head, File audio, String tail) {
        try {
            return new SequenceInputStream(java.util.Collections.enumeration(List.of(
                    new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)),
                    new FileInputStream(audio),
                    new ByteArrayInputStream(tail.getBytes(StandardCharsets.UTF_8)))));
        } catch (Exception e) {
            throw new IllegalStateException("音频读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试用钩子：单测覆写这个方法替换 HTTP 往返。
     * 真发请求会让测试结果取决于本机有没有装 asr-service，那样测试就不可信了。
     *
     * @return /health 的响应体；连不上 / 非 200 一律回 null
     */
    String getHealth(String base) {
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/health")).timeout(PROBE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 「没装组件」是最常见的分支，不该刷 WARN 日志
            log.debug("本机转写探测失败 {}: {}", base, e.toString());
            return null;
        }
    }
}
