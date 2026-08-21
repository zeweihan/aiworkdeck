package com.checkba.service;

import com.checkba.exception.FeatureNotConfiguredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;

/**
 * 语音合成，只有本机一档：桌面包内置的 Kokoro 服务（OpenAI 兼容 /v1）。
 *
 * <p>云端 ElevenLabs 那一档已整体移除：打包态本来就默认走本机引擎，云端档从没有默认生效过；
 * 而把它放进平台代采意味着转售第三方语音合成，其商用条款对转售另有约定。本机引擎免费、
 * 不出本机，对律师用户是更干净的口径，没有保留一条云端通路的理由。
 */
@Service
public class TtsService {

    private static final Logger logger = LoggerFactory.getLogger(TtsService.class);
    private static final String TEMP_AUDIO_DIR = System.getProperty("java.io.tmpdir") + File.separator + "awd_tts_audio";

    private final SystemSettingService systemSettingService;

    @Value("${external.tts.local-base-url:}")
    private String defaultLocalBaseUrl;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TtsService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
        new File(TEMP_AUDIO_DIR).mkdirs();
    }

    private static RestTemplate buildRestTemplate() {
        // 显式超时：此前无 connect/read timeout，本地 Kokoro 网络卡死会无限期挂起并占满请求线程。
        // TTS 合成可能较慢，read 超时给宽松值。
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    /**
     * 定时清理临时音频目录：合成的 wav 写入 TEMP_AUDIO_DIR 后返回 File，此前无任何清理，
     * 长期运行会撑满系统临时目录。每小时清理超过 1 小时未修改的文件（保留最近文件供下载/播放）。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60 * 60 * 1000)
    public void cleanupOldAudioFiles() {
        File[] files = new File(TEMP_AUDIO_DIR).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff && !f.delete()) {
                logger.warn("Failed to delete stale TTS audio: {}", f.getName());
            }
        }
    }

    // settings（系统管理可改）> env/yml 默认
    private String localBaseUrl() {
        return systemSettingService.get("external.tts.localBaseUrl", defaultLocalBaseUrl);
    }

    // rate 容错解析成 Kokoro 的 speed：支持 "1.2" / "1.2x"，解析失败回 1.0
    static double parseSpeed(String rate) {
        if (rate == null || rate.isBlank()) return 1.0;
        try {
            double v = Double.parseDouble(rate.trim().replaceAll("[xX]$", ""));
            return (v >= 0.5 && v <= 2.0) ? v : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /**
     * 本地 Kokoro 服务的音色列表（GET /v1/audio/voices，OpenAI 风格包装层）
     */
    public List<VoiceOption> getVoices() {
        String base = localBaseUrl();
        if (base == null || base.isBlank()) {
            logger.warn("TTS local base url is empty; speech component is not ready");
            return new ArrayList<>();
        }
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(base + "/v1/audio/voices", String.class);
            List<VoiceOption> result = new ArrayList<>();
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode voicesNode = objectMapper.readTree(response.getBody()).get("voices");
                if (voicesNode != null && voicesNode.isArray()) {
                    for (JsonNode v : voicesNode) {
                        VoiceOption vo = new VoiceOption();
                        vo.setVoiceId(v.path("voiceId").asText());
                        vo.setName(v.path("name").asText());
                        vo.setGender(v.path("gender").asText("unknown"));
                        vo.setLocale(v.path("locale").asText(""));
                        result.add(vo);
                    }
                }
            }
            logger.info("Loaded {} voices from local Kokoro", result.size());
            return result;
        } catch (Exception e) {
            logger.error("Failed to list voices from local Kokoro at {}", base, e);
            return new ArrayList<>();
        }
    }

    /**
     * 本地 Kokoro 合成（POST /v1/audio/speech → WAV）。
     * 服务不可达 = 组件未下载/未启用，走"功能未配置"引导（前端既有机制）。
     *
     * @param rate 语速，"1.2" / "1.2x" 皆可。
     *             <b>pitch 与 volume 两个形参已去掉</b>：本地引擎不支持，而前端那两个滑杆
     *             同样已删（它们从来没生效过——前端把 '+0Hz'/'+0%' 写死在 payload 里，
     *             后端也从不读）。留着形参位只会让下一个人以为「传了就能生效」。
     *             {@code TtsController.GenerateRequest} 仍保留这两个 setter，
     *             免得存量客户端的请求体反序列化炸掉，但不再往下传。
     */
    public File generateAudio(String text, String voiceId, String rate) {
        String base = localBaseUrl();
        if (base == null || base.isBlank()) {
            throw new FeatureNotConfiguredException("tts",
                    LangText.of("本地语音组件未就绪：请在「系统管理 → 组件管理」下载语音组件",
                            "Local speech component is not ready: download it in Admin → Components."));
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("input", text);
            if (voiceId != null && !voiceId.isEmpty()) body.put("voice", voiceId);
            body.put("speed", parseSpeed(rate));

            logger.info("Generating TTS via local Kokoro: voice={}, text length={}", voiceId, text.length());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(base + "/v1/audio/speech", HttpMethod.POST, entity, byte[].class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("local TTS generation failed: " + response.getStatusCode());
            }
            File outputFile = new File(TEMP_AUDIO_DIR, UUID.randomUUID() + ".wav");
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(response.getBody());
            }
            logger.info("Saved local TTS audio to: {}", outputFile.getAbsolutePath());
            return outputFile;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            if (!isServiceUnreachable(e)) {
                // 组件在跑，只是这次合成没在读超时内给出结果。报成「请去下载组件」
                // 会把用户支到一个和故障完全无关的地方，而真正该做的是缩短文本或重试。
                logger.warn("Local TTS timed out (component is running): {}", e.getMessage());
                throw new RuntimeException(LangText.of(
                        "本地语音合成超时：文本过长或组件繁忙，请缩短文本后重试",
                        "Local speech synthesis timed out: shorten the text or try again."), e);
            }
            // 连不上：组件未启动（未下载或被删除）
            throw new FeatureNotConfiguredException("tts",
                    LangText.of("本地语音组件未就绪：请在「系统管理 → 组件管理」下载并启用语音组件",
                            "Local speech component is not running: download & enable it in Admin → Components."));
        } catch (FeatureNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to generate audio via local Kokoro", e);
            throw new RuntimeException("Failed to generate audio: " + e.getMessage(), e);
        }
    }

    /**
     * {@code ResourceAccessException} 把「连不上」和「连上了但太慢」裹成同一个类型，
     * 于是一次读超时会被报成「组件未下载」——用户被支去重装一个正在正常运行的组件。
     * 这里按 cause 分开：只有确实连不上才算「未就绪」。
     *
     * <p>读超时归为「可达但失败」。本地回环连不上是立刻 ECONNREFUSED，不会走到
     * socket 超时，所以把 SocketTimeoutException 一律当成「服务在跑但没按时回」是安全的。
     * 认不出来的 cause 保持旧行为（多数确实是连不上）。
     */
    static boolean isServiceUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) return false;
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return true;
    }

    // Response DTOs
    public static class VoiceOption {
        private String voiceId;
        private String name;
        private String gender;
        private String locale;
        private String cnName;

        public String getVoiceId() { return voiceId; }
        public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getLocale() { return locale; }
        public void setLocale(String locale) { this.locale = locale; }
        public String getCnName() { return cnName; }
        public void setCnName(String cnName) { this.cnName = cnName; }
    }
}
