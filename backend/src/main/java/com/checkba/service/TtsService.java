package com.checkba.service;

import com.checkba.exception.FeatureNotConfiguredException;
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
 * TTS Service using ElevenLabs API
 * API Documentation: https://elevenlabs.io/docs/api-reference
 */
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TtsService {

    private static final Logger logger = LoggerFactory.getLogger(TtsService.class);
    private static final String TEMP_AUDIO_DIR = System.getProperty("java.io.tmpdir") + File.separator + "elevenlabs_audio";
    
    @Autowired
    private SystemSettingService systemSettingService;

    @Value("${external.elevenlabs.api-key}")
    private String defaultApiKey;
    
    @Value("${external.elevenlabs.base-url}")
    private String defaultBaseUrl;
    
    @Value("${external.elevenlabs.model-id}")
    private String defaultModelId;
    
    @Value("${external.elevenlabs.default-voice-id}")
    private String defaultDefaultVoiceId;

    // 语音提供方：elevenlabs（云端，默认）| local（桌面捆绑 Kokoro，OpenAI 兼容 /v1）
    @Value("${external.tts.provider:elevenlabs}")
    private String defaultTtsProvider;

    @Value("${external.tts.local-base-url:}")
    private String defaultLocalBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TtsService() {
        new File(TEMP_AUDIO_DIR).mkdirs();
    }

    /**
     * Get available voices from ElevenLabs API
     * GET /voices
     */
    // settings（系统管理可改）> env/yml 默认
    private boolean isLocalProvider() {
        return "local".equalsIgnoreCase(systemSettingService.get("external.tts.provider", defaultTtsProvider));
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

    public List<VoiceOption> getVoices() {
        if (isLocalProvider()) {
            return getLocalVoices();
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
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode voicesNode = root.get("voices");
                
                List<VoiceOption> result = new ArrayList<>();
                if (voicesNode != null && voicesNode.isArray()) {
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
                }
                
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
     * Generate audio from text using ElevenLabs TTS API
     * POST /text-to-speech/{voice_id}
     * 
     * @param text Text to convert to speech
     * @param voiceId ElevenLabs voice ID (or voice name for backward compatibility)
     * @param rate Unused (ElevenLabs uses different settings)
     * @param pitch Unused (ElevenLabs uses different settings)
     * @param volume Unused (ElevenLabs uses different settings)
     */
    public File generateAudio(String text, String voiceId, String rate, String pitch, String volume) {
        if (isLocalProvider()) {
            return generateLocalAudio(text, voiceId, rate);
        }
        // 未配置 TTS 密钥时直接返回"功能未配置"，前端引导去设置（#18 T5）
        String configuredApiKey = systemSettingService.get("external.elevenlabs.apiKey", defaultApiKey);
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            throw new FeatureNotConfiguredException("tts",
                    "语音合成未配置：请在设置中配置 ElevenLabs TTS / "
                            + "Text-to-speech is not configured: set up ElevenLabs in Settings.");
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
                    "本地语音组件未就绪：请在「系统管理 → 组件管理」下载语音组件 / "
                            + "Local speech component is not ready: download it in Admin → Components.");
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
            // 连接被拒/超时：组件未启动（未下载或被删除）
            throw new FeatureNotConfiguredException("tts",
                    "本地语音组件未就绪：请在「系统管理 → 组件管理」下载并启用语音组件 / "
                            + "Local speech component is not running: download & enable it in Admin → Components.");
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
