package com.checkba.service.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 语音转写（可选）。
 *
 * <p><b>为什么是可选的：</b>本产品没有捆绑离线 ASR（打包里只有 Kokoro TTS 与 MinerU OCR），
 * 塞一个 whisper 进安装包会让体积翻倍。所以这里只留一个 OpenAI 兼容的
 * {@code POST {base}/audio/transcriptions} 接口位：配了就转写，没配就把语音原样存成附件。
 *
 * <p><b>没转写的语音不会被静默吞掉</b>：优化者分诊时看到「有语音但无转写」会直接判
 * 「需要人听」走邮件出口，并把附件下载地址写进邮件——宁可多打扰一次，也不能让一条
 * 用户认真录的反馈变成库里一行看不懂的记录。
 */
@Slf4j
@Service
public class VoiceTranscriptionService {

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 可注入的传输层（测试用桩）。 */
    public interface Transport {
        String post(String url, String apiKey, String contentType, byte[] body) throws Exception;
    }

    // 两个构造器（另一个是测试用的、可注入 transport），Spring 必须被明确告知选哪个
    @org.springframework.beans.factory.annotation.Autowired
    public VoiceTranscriptionService(
            @Value("${feedback.transcription.enabled:false}") boolean enabled,
            @Value("${feedback.transcription.base-url:}") String baseUrl,
            @Value("${feedback.transcription.api-key:}") String apiKey,
            @Value("${feedback.transcription.model:whisper-1}") String model) {
        this(enabled, baseUrl, apiKey, model, null);
    }

    VoiceTranscriptionService(boolean enabled, String baseUrl, String apiKey, String model, Transport transport) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "whisper-1" : model.trim();
        this.transport = transport != null ? transport : defaultTransport();
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, key, contentType, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!key.isEmpty()) b.header("Authorization", "Bearer " + key);
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("转写服务返回 " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            return resp.body();
        };
    }

    public boolean isEnabled() {
        return enabled && !baseUrl.isEmpty();
    }

    /**
     * 转写一段音频。未启用或失败一律返回空串——转写只是锦上添花，
     * 绝不能因为它挂了就让用户提交不了反馈。
     */
    public String transcribe(byte[] audio, String filename, String contentType) {
        if (!isEnabled() || audio == null || audio.length == 0) return "";
        try {
            String boundary = "----awdfb" + Long.toHexString(System.nanoTime());
            byte[] body = multipart(boundary, audio, filename, contentType);
            String raw = transport.post(baseUrl + "/audio/transcriptions", apiKey,
                    "multipart/form-data; boundary=" + boundary, body);
            JsonNode node = mapper.readTree(raw);
            JsonNode text = node.get("text");
            return text == null ? "" : text.asText("");
        } catch (Exception e) {
            log.warn("语音转写失败（附件仍已保存）: {}", e.toString());
            return "";
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String filename, String contentType) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String safeName = (filename == null || filename.isBlank()) ? "voice.webm" : filename;
        String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + safeName + "\"\r\nContent-Type: " + ct + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
