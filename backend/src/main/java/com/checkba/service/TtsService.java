package com.checkba.service;

import com.checkba.exception.FeatureNotConfiguredException;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * 语音合成：平台代采（网关）/ 自备 ElevenLabs Key / 本地 Kokoro 三档。
 *
 * <p>档位判定<b>一律走 {@link com.checkba.service.platform.ExternalProviderResolver}</b>，
 * 不再自己读设置字符串：那样读的话，D5（平台档只在 local-mode 开放）与存量回填
 * 这两条就要在这里再实现一遍，而它们只该有一处判据。
 *
 * <p>存量取值 {@code elevenlabs} 由 {@code ExternalServiceProvider.parse} 映射成 BYOK，
 * 桌面打包态注入的 {@code EXTERNAL_TTS_PROVIDER=local} 由 {@code ExternalProviderBackfill}
 * 回填成 {@code local}——两条存量路径都不经过这里，改这里不会把它们绕过去。
 *
 * API Documentation: https://elevenlabs.io/docs/api-reference
 */
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TtsService {

    private static final Logger logger = LoggerFactory.getLogger(TtsService.class);
    private static final String TEMP_AUDIO_DIR = System.getProperty("java.io.tmpdir") + File.separator + "elevenlabs_audio";

    /** 长文本合成过 5 秒是常态，账户通道那 5 秒在这里必然误判成故障。 */
    private static final int GATEWAY_TIMEOUT_SECONDS = 60;

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private com.checkba.service.platform.ExternalProviderResolver externalProviderResolver;

    @Autowired
    private com.checkba.service.platform.PlatformGatewayClient platformGatewayClient;

    @Value("${external.elevenlabs.api-key}")
    private String defaultApiKey;
    
    @Value("${external.elevenlabs.base-url}")
    private String defaultBaseUrl;
    
    @Value("${external.elevenlabs.model-id}")
    private String defaultModelId;
    
    @Value("${external.elevenlabs.default-voice-id}")
    private String defaultDefaultVoiceId;

    @Value("${external.tts.local-base-url:}")
    private String defaultLocalBaseUrl;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate buildRestTemplate() {
        // 显式超时：此前无 connect/read timeout，ElevenLabs 或本地 Kokoro 网络卡死会无限期
        // 挂起并占满请求线程。TTS 合成可能较慢，read 超时给宽松值。
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    public TtsService() {
        new File(TEMP_AUDIO_DIR).mkdirs();
    }

    /**
     * 定时清理临时音频目录：合成的 mp3/wav 写入 TEMP_AUDIO_DIR 后返回 File，此前无任何清理，
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

    /**
     * Get available voices from ElevenLabs API
     * GET /voices
     */
    /** 当前生效的档位。判据只有这一处（D5 的 local-mode 闸也在它里面）。 */
    private com.checkba.service.platform.ExternalServiceProvider tier() {
        return externalProviderResolver.resolve(
                com.checkba.service.platform.ExternalServiceProvider.TTS);
    }

    private boolean isLocalProvider() {
        return tier() == com.checkba.service.platform.ExternalServiceProvider.LOCAL;
    }

    private boolean isPlatformProvider() {
        return tier() == com.checkba.service.platform.ExternalServiceProvider.PLATFORM;
    }

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
     * TTS 未配置时的统一说法。<b>不要再单点 ElevenLabs</b>：三档里能落地的只有本地
     * Kokoro 与平台代采，把用户往一个他多半没有、也不打算买的云服务上引，
     * 等于把「怎么把它跑起来」这一步藏了起来。
     */
    private static FeatureNotConfiguredException ttsNotConfigured() {
        return new FeatureNotConfiguredException("tts",
                LangText.of("语音合成未就绪：请在「系统管理 → 平台服务 → 语音合成」选择本地引擎"
                                + "（需先在「组件管理」下载语音组件），或连接账户改用平台代采。",
                        "Text-to-speech is not ready: pick the local engine under "
                                + "Admin → Platform Services → Speech (download the speech component first), "
                                + "or connect an account to use the platform tier."));
    }

    public List<VoiceOption> getVoices() {
        if (isLocalProvider()) {
            return getLocalVoices();
        }
        if (isPlatformProvider()) {
            return getPlatformVoices();
        }
        try {
            String baseUrl = systemSettingService.get("external.elevenlabs.baseUrl", defaultBaseUrl);
            String apiKey = systemSettingService.get("external.elevenlabs.apiKey", defaultApiKey);

            String url = baseUrl + "/voices";

            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", apiKey);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<VoiceOption> result = parseElevenLabsVoices(objectMapper.readTree(response.getBody()));
                logger.info("Loaded {} voices from ElevenLabs", result.size());
                return result;
            }

            logger.warn("Voice list response unsuccessful: {}", response.getStatusCode());
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to list voices from ElevenLabs API", e);
            return new ArrayList<>();
        }
    }

    /**
     * 平台代采档的音色列表。网关把 ElevenLabs 的原始响应原样带回来，
     * 所以解析与 byok 档共用一份——两档的音色 ID 必须是同一套，
     * 否则用户在设置里选好的音色一换档就指向不存在的东西。
     *
     * <p>失败回空列表而不是抛：这个方法的既有契约就是「拿不到就是空」
     * （下拉框空着而不是整页报错），真正的失败会在合成那一刻带着原因浮出来。
     */
    private List<VoiceOption> getPlatformVoices() {
        try {
            PlatformGatewayClient.Result result =
                    platformGatewayClient.call("tts", "voices", Map.of(), 15);
            List<VoiceOption> voices = parseElevenLabsVoices(result.data());
            logger.info("Loaded {} voices via platform gateway", voices.size());
            return voices;
        } catch (com.checkba.service.platform.GatewayException e) {
            logger.warn("平台音色列表获取失败 kind={}: {}", e.getKind(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /** ElevenLabs 的 /voices 响应 → 音色选项。平台档与自备 Key 档共用，避免格式漂移。 */
    private List<VoiceOption> parseElevenLabsVoices(JsonNode root) {
        List<VoiceOption> result = new ArrayList<>();
        JsonNode voicesNode = root == null ? null : root.get("voices");
        if (voicesNode == null || !voicesNode.isArray()) {
            return result;
        }
        for (JsonNode voiceNode : voicesNode) {
            VoiceOption vo = new VoiceOption();
            vo.setVoiceId(voiceNode.path("voice_id").asText());
            vo.setName(voiceNode.path("name").asText());

            // Extract gender and locale from labels
            JsonNode labels = voiceNode.get("labels");
            if (labels != null) {
                vo.setGender(labels.path("gender").asText("unknown"));
                vo.setLocale(labels.path("accent").asText(""));
            } else {
                vo.setGender("unknown");
                vo.setLocale("");
            }

            result.add(vo);
        }
        return result;
    }

    /**
     * Generate audio from text using ElevenLabs TTS API
     * POST /text-to-speech/{voice_id}
     * 
     * @param text Text to convert to speech
     * @param voiceId ElevenLabs voice ID (or voice name for backward compatibility)
     * @param rate 语速倍率（"1.0" / "1.2x"）。<b>只有本地 Kokoro 档吃这个参数</b>，
     *             ElevenLabs 与平台代采档没有对应的 API 字段，传了也不会生效。
     *             此前这里还有 pitch/volume 两个形参，三档后端一个都不读——
     *             留着只会让前端画出三个点了没反应的滑杆，已一并去掉。
     */
    public File generateAudio(String text, String voiceId, String rate) {
        if (isLocalProvider()) {
            return generateLocalAudio(text, voiceId, rate);
        }
        if (isPlatformProvider()) {
            return generatePlatformAudio(text, voiceId);
        }
        // 未配置 TTS 密钥时直接返回"功能未配置"，前端引导去设置（#18 T5）
        String configuredApiKey = systemSettingService.get("external.elevenlabs.apiKey", defaultApiKey);
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            throw ttsNotConfigured();
        }
        try {
            String baseUrl = systemSettingService.get("external.elevenlabs.baseUrl", defaultBaseUrl);
            String apiKey = systemSettingService.get("external.elevenlabs.apiKey", defaultApiKey);
            String modelId = systemSettingService.get("external.elevenlabs.modelId", defaultModelId);
            String defaultVoiceId = systemSettingService.get("external.elevenlabs.defaultVoiceId", defaultDefaultVoiceId);

            // Use default voice if not specified
            if (voiceId == null || voiceId.isEmpty()) {
                voiceId = defaultVoiceId;
            }
            
            String url = baseUrl + "/text-to-speech/" + voiceId;
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.parseMediaType("audio/mpeg")));
            
            // Build request body
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("model_id", modelId);
            
            // Voice settings (use defaults for natural sound)
            Map<String, Object> voiceSettings = new HashMap<>();
            voiceSettings.put("stability", 0.5);
            voiceSettings.put("similarity_boost", 0.75);
            voiceSettings.put("style", 0.0);
            voiceSettings.put("use_speaker_boost", true);
            body.put("voice_settings", voiceSettings);
            
            logger.info("Generating TTS via ElevenLabs: voice={}, text length={}", voiceId, text.length());
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("TTS generation failed: " + response.getStatusCode());
            }
            
            byte[] audioData = response.getBody();
            logger.info("Received audio data: {} bytes", audioData.length);
            
            // Save to temp file
            String outName = UUID.randomUUID().toString() + ".mp3";
            File outputFile = new File(TEMP_AUDIO_DIR, outName);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(audioData);
            }
            
            logger.info("Saved audio to: {}", outputFile.getAbsolutePath());
            return outputFile;

        } catch (Exception e) {
            logger.error("Failed to generate audio via ElevenLabs", e);
            throw new RuntimeException("Failed to generate audio: " + e.getMessage(), e);
        }
    }

    /**
     * 平台代采档合成：官网持凭证调 ElevenLabs，按实际字符数扣 Credits。
     *
     * <p>{@code rate/pitch/volume} 与自备 Key 档一样被忽略——ElevenLabs 没有这几个参数，
     * 假装支持只会让两档产出听感不同的音频。
     *
     * <p>{@link com.checkba.service.platform.GatewayException} 原样抛出去：
     * 包成 RuntimeException 会让四种失败全变成一句「服务器内部错误」，
     * 而它们的下一步分别是充值 / 等一等 / 改用自己的 Key / 切本地引擎。
     */
    private File generatePlatformAudio(String text, String voiceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        if (voiceId != null && !voiceId.isEmpty()) params.put("voiceId", voiceId);

        PlatformGatewayClient.Result result =
                platformGatewayClient.call("tts", "speech", params, GATEWAY_TIMEOUT_SECONDS);
        String audioBase64 = result.data().path("audioBase64").asText("");
        if (audioBase64.isEmpty()) {
            throw new RuntimeException("平台语音合成未返回音频");
        }
        byte[] audio = Base64.getDecoder().decode(audioBase64);
        File outputFile = new File(TEMP_AUDIO_DIR, UUID.randomUUID() + ".mp3");
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(audio);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save audio: " + e.getMessage(), e);
        }
        logger.info("Saved platform TTS audio to: {} ({} bytes, {} {})",
                outputFile.getAbsolutePath(), audio.length, result.units(), result.unit());
        return outputFile;
    }

    /**
     * 本地 Kokoro 服务的音色列表（GET /v1/audio/voices，OpenAI 风格包装层）
     */
    private List<VoiceOption> getLocalVoices() {
        String base = localBaseUrl();
        if (base == null || base.isBlank()) {
            logger.warn("TTS provider=local but external.tts.local-base-url is empty");
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
     */
    private File generateLocalAudio(String text, String voiceId, String rate) {
        String base = localBaseUrl();
        if (base == null || base.isBlank()) {
            throw new FeatureNotConfiguredException("tts",
                    LangText.of("本地语音组件未就绪：请在「系统管理 → 组件管理」下载语音组件",
                            "Local speech component is not ready: download it in Admin → Components."));
        }
        // 端口分配了但服务没起来是常态：kokoro-service 在打包态被 modelManager
        // 的 isInstalled('kokoro-models') 卡着，300MB 模型没下就不会启动。
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
            // 连接被拒/超时：组件未启动（未下载或被删除）
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

    // Response DTOs
    public static class VoiceOption {
        private String voiceId;  // ElevenLabs voice ID
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
