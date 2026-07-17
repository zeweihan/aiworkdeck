package com.checkba.controller;

import com.checkba.config.AiModelProperties;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.SystemSettingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台管理配置接口：
 * - 外部服务供应商配置（key / secret / baseUrl 等）
 * - AI 服务系统提示词
 * - AI 服务激活的供应商
 * - 用户管理（只读列表）
 *
 * 说明：
 * - 仅允许 admin 用户调用（基于现有 session 机制）
 * - 将可变配置写入 system_setting 表，默认值来自 application.yml
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final SystemSettingService systemSettingService;
    private final UserRepository userRepository;
    private final AiModelProperties aiModelProperties;
    private final com.checkba.service.AdminAccessService adminAccessService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.checkba.service.ai.ChatModelFactory chatModelFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminConfigController(SystemSettingService systemSettingService,
                                 UserRepository userRepository,
                                 AiModelProperties aiModelProperties,
                                 com.checkba.service.AdminAccessService adminAccessService,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                 com.checkba.service.ai.ChatModelFactory chatModelFactory) {
        this.systemSettingService = systemSettingService;
        this.userRepository = userRepository;
        this.aiModelProperties = aiModelProperties;
        this.adminAccessService = adminAccessService;
        this.objectMapper = objectMapper;
        this.chatModelFactory = chatModelFactory;
    }

    // ... (rest of the file remains same until DTOs)

    // ... (lines 43-475 skipped, need to pinpoint where DTOs start or if I can replace just the DTOs and Constructor)
    // Using multiple chunks to be safer.

    // ...


    // ==== 默认值：来自 application.yml ====

    @Value("${external.qichacha.base-url:}")
    private String defaultQichachaBaseUrl;

    @Value("${external.qichacha.key:}")
    private String defaultQichachaKey;

    @Value("${external.qichacha.secret:}")
    private String defaultQichachaSecret;

    @Value("${external.tushare.base-url:http://api.tushare.pro}")
    private String defaultTushareBaseUrl;

    @Value("${external.tushare.token:}")
    private String defaultTushareToken;

    // Aliyun OCR 默认值
    @Value("${external.aliyun-ocr.access-key-id:}")
    private String defaultAliyunOcrAccessKeyId;

    @Value("${external.aliyun-ocr.access-key-secret:}")
    private String defaultAliyunOcrAccessKeySecret;

    @Value("${external.aliyun-ocr.endpoint:}")
    private String defaultAliyunOcrEndpoint;

    @Value("${external.aliyun-ocr.region-id:}")
    private String defaultAliyunOcrRegionId;

    @Value("${external.aliyun-ocr.public-base-url:}")
    private String defaultAliyunOcrPublicBaseUrl;

    // PKULaw 默认值
    @Value("${external.pkulaw.token:}")
    private String defaultPkulawToken;

    // 博查搜索默认值（search_web 工具使用；与 WebTools 的 bocha.api.key 同源）
    @Value("${bocha.api.key:}")
    private String defaultBochaApiKey;

    // ElevenLabs 默认值
    @Value("${external.elevenlabs.api-key:}")
    private String defaultElevenLabsApiKey;

    @Value("${external.elevenlabs.base-url:https://api.elevenlabs.io/v1}")
    private String defaultElevenLabsBaseUrl;

    @Value("${external.elevenlabs.model-id:eleven_multilingual_v2}")
    private String defaultElevenLabsModelId;

    @Value("${external.elevenlabs.default-voice-id:JBFqnCBsd6RMkjVDRZzb}")
    private String defaultElevenLabsDefaultVoiceId;

    // OpenRouter 默认值
    @Value("${ai.model.open-router.api-key:}")
    private String defaultOpenRouterApiKey;

    @Value("${ai.model.open-router.base-url:https://openrouter.ai/api/v1}")
    private String defaultOpenRouterBaseUrl;

    // Google / Gemini 默认值来自 AiModelProperties

    // === 配置 key 常量 ===
    // AI - System Prompts
    private static final String KEY_AI_ACTIVE_PROVIDER = "ai.activeProvider";
    private static final String KEY_AI_SYSTEM_PROMPT_OLLAMA = "ai.systemPrompt.OLLAMA";
    private static final String KEY_AI_SYSTEM_PROMPT_GEMINI = "ai.systemPrompt.GEMINI";
    private static final String KEY_AI_ASSISTANTS = "ai.assistants";

    // Qichacha
    private static final String KEY_QICHACHA_BASE_URL = "external.qichacha.baseUrl";
    private static final String KEY_QICHACHA_KEY = "external.qichacha.key";
    private static final String KEY_QICHACHA_SECRET = "external.qichacha.secret";

    // Tushare
    private static final String KEY_TUSHARE_BASE_URL = "external.tushare.baseUrl";
    private static final String KEY_TUSHARE_TOKEN = "external.tushare.token";

    // Aliyun OCR
    private static final String KEY_ALIYUN_OCR_ACCESS_KEY_ID = "external.aliyunOcr.accessKeyId";
    private static final String KEY_ALIYUN_OCR_ACCESS_KEY_SECRET = "external.aliyunOcr.accessKeySecret";
    private static final String KEY_ALIYUN_OCR_ENDPOINT = "external.aliyunOcr.endpoint";
    private static final String KEY_ALIYUN_OCR_REGION_ID = "external.aliyunOcr.regionId";
    private static final String KEY_ALIYUN_OCR_PUBLIC_BASE_URL = "external.aliyunOcr.publicBaseUrl";

    // PKULaw
    private static final String KEY_PKULAW_TOKEN = "external.pkulaw.token";

    // 博查搜索（Bocha AI）
    private static final String KEY_BOCHA_API_KEY = "external.bocha.apiKey";

    // ElevenLabs
    private static final String KEY_ELEVENLABS_API_KEY = "external.elevenlabs.apiKey";
    private static final String KEY_ELEVENLABS_BASE_URL = "external.elevenlabs.baseUrl";
    private static final String KEY_ELEVENLABS_MODEL_ID = "external.elevenlabs.modelId";
    private static final String KEY_ELEVENLABS_DEFAULT_VOICE_ID = "external.elevenlabs.defaultVoiceId";

    // OpenRouter
    private static final String KEY_OPENROUTER_API_KEY = "external.openrouter.apiKey";
    private static final String KEY_OPENROUTER_BASE_URL = "external.openrouter.baseUrl";

    // Google / Gemini
    private static final String KEY_GOOGLE_API_KEY = "external.google.apiKey";
    private static final String KEY_GOOGLE_MODEL_NAME = "external.google.modelName";
    private static final String KEY_GOOGLE_API_BASE_URL = "external.google.apiBaseUrl";

    // ============ 配置读取 =============

    @GetMapping("/config")
    public ResponseEntity<?> getAdminConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        User admin = requireAdmin(sessionId);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("仅管理员可访问此接口"));
        }

        // 外部服务默认值
        Map<String, String> defaults = new HashMap<>();
        defaults.put(KEY_QICHACHA_BASE_URL, defaultQichachaBaseUrl);
        defaults.put(KEY_QICHACHA_KEY, defaultQichachaKey);
        defaults.put(KEY_QICHACHA_SECRET, defaultQichachaSecret);
        defaults.put(KEY_TUSHARE_BASE_URL, defaultTushareBaseUrl);
        defaults.put(KEY_TUSHARE_TOKEN, defaultTushareToken);
        defaults.put(KEY_ALIYUN_OCR_ACCESS_KEY_ID, defaultAliyunOcrAccessKeyId);
        defaults.put(KEY_ALIYUN_OCR_ACCESS_KEY_SECRET, defaultAliyunOcrAccessKeySecret);
        // 给出开箱即用的默认值：cn-hangzhou 通用 OCR Endpoint
        defaults.put(KEY_ALIYUN_OCR_ENDPOINT,
                (defaultAliyunOcrEndpoint == null || defaultAliyunOcrEndpoint.isBlank())
                        ? "ocr-api.cn-hangzhou.aliyuncs.com"
                        : defaultAliyunOcrEndpoint);
        defaults.put(KEY_ALIYUN_OCR_REGION_ID,
                (defaultAliyunOcrRegionId == null || defaultAliyunOcrRegionId.isBlank())
                        ? "cn-hangzhou"
                        : defaultAliyunOcrRegionId);
        defaults.put(KEY_ALIYUN_OCR_PUBLIC_BASE_URL,
                defaultAliyunOcrPublicBaseUrl == null ? "" : defaultAliyunOcrPublicBaseUrl);

        // PKULaw
        defaults.put(KEY_PKULAW_TOKEN, defaultPkulawToken);

        // 博查搜索
        defaults.put(KEY_BOCHA_API_KEY, defaultBochaApiKey);

        // ElevenLabs
        defaults.put(KEY_ELEVENLABS_API_KEY, defaultElevenLabsApiKey);
        defaults.put(KEY_ELEVENLABS_BASE_URL, defaultElevenLabsBaseUrl);
        defaults.put(KEY_ELEVENLABS_MODEL_ID, defaultElevenLabsModelId);
        defaults.put(KEY_ELEVENLABS_DEFAULT_VOICE_ID, defaultElevenLabsDefaultVoiceId);

        // OpenRouter
        defaults.put(KEY_OPENROUTER_API_KEY, defaultOpenRouterApiKey);
        defaults.put(KEY_OPENROUTER_BASE_URL, defaultOpenRouterBaseUrl);

        // Google / Gemini 默认值来自配置类
        defaults.put(KEY_GOOGLE_API_KEY, aiModelProperties.getGemini().getApiKey());
        defaults.put(KEY_GOOGLE_MODEL_NAME, aiModelProperties.getGemini().getModelName());
        defaults.put(KEY_GOOGLE_API_BASE_URL, aiModelProperties.getGemini().getApiBaseUrl());

        // AI 默认值
        defaults.put(KEY_AI_ACTIVE_PROVIDER,
                aiModelProperties.getProvider() != null
                        ? aiModelProperties.getProvider().name()
                        : AiModelProperties.Provider.OLLAMA.name());

        // 当前存储值（DB > 默认值）
        Map<String, String> all = systemSettingService.getMany(defaults);

        AdminConfigResponse resp = new AdminConfigResponse();

        // 外部服务
        ExternalServicesConfig external = new ExternalServicesConfig();
        external.setGoogle(new GoogleConfig(
                all.get(KEY_GOOGLE_API_KEY),
                all.get(KEY_GOOGLE_MODEL_NAME),
                all.get(KEY_GOOGLE_API_BASE_URL)
        ));
        external.setOpenRouter(new OpenRouterConfig(
                all.get(KEY_OPENROUTER_API_KEY),
                all.get(KEY_OPENROUTER_BASE_URL)
        ));
        external.setQichacha(new QichachaConfig(
                all.get(KEY_QICHACHA_BASE_URL),
                all.get(KEY_QICHACHA_KEY),
                all.get(KEY_QICHACHA_SECRET)
        ));
        external.setTushare(new TushareConfig(
                all.get(KEY_TUSHARE_BASE_URL),
                all.get(KEY_TUSHARE_TOKEN)
        ));
        external.setAliyunOcr(new AliyunOcrConfig(
                all.get(KEY_ALIYUN_OCR_ACCESS_KEY_ID),
                all.get(KEY_ALIYUN_OCR_ACCESS_KEY_SECRET),
                all.get(KEY_ALIYUN_OCR_ENDPOINT),
                all.get(KEY_ALIYUN_OCR_REGION_ID),
                all.get(KEY_ALIYUN_OCR_PUBLIC_BASE_URL)
        ));
        external.setPkulaw(new PkulawConfig(
                all.get(KEY_PKULAW_TOKEN)
        ));
        external.setBocha(new BochaConfig(
                all.get(KEY_BOCHA_API_KEY)
        ));
        external.setElevenLabs(new ElevenLabsConfig(
                all.get(KEY_ELEVENLABS_API_KEY),
                all.get(KEY_ELEVENLABS_BASE_URL),
                all.get(KEY_ELEVENLABS_MODEL_ID),
                all.get(KEY_ELEVENLABS_DEFAULT_VOICE_ID)
        ));
        resp.setExternal(external);

        // AI 配置
        AiConfig ai = new AiConfig();
        String activeProvider = all.get(KEY_AI_ACTIVE_PROVIDER);
        ai.setActiveProvider(activeProvider);
        
        // No fallback as requested
        ai.setSystemPromptOllama(systemSettingService.get(KEY_AI_SYSTEM_PROMPT_OLLAMA, ""));
        ai.setSystemPromptGemini(systemSettingService.get(KEY_AI_SYSTEM_PROMPT_GEMINI, ""));
        
        // Assistants logic: DB only, no fallback
        String assistantsJson = systemSettingService.get(KEY_AI_ASSISTANTS, null);
        if (assistantsJson != null && !assistantsJson.isBlank()) {
            try {
                List<com.checkba.model.ai.AiAssistantConfig> list = objectMapper.readValue(assistantsJson, new com.fasterxml.jackson.core.type.TypeReference<List<com.checkba.model.ai.AiAssistantConfig>>() {});
                ai.setAssistants(list);
            } catch (Exception e) {
                // Log error but return empty list (or handle appropriately)
                e.printStackTrace();
            }
        }

        resp.setAi(ai);

        return ResponseEntity.ok(resp);
    }

    /**
     * 更新系统配置（外部服务 + AI）
     */
    @PostMapping("/config")
    public ResponseEntity<?> updateAdminConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody AdminConfigUpdateRequest request) {

        User admin = requireAdmin(sessionId);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("仅管理员可访问此接口"));
        }

        Map<String, String> updates;
        try {
            updates = toSettingsUpdates(request, objectMapper);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }

        systemSettingService.setMany(updates);
        // 立即让新的 key/baseUrl/供应商生效，避免旧配置的模型实例缓存到进程重启
        chatModelFactory.clearCache();

        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "保存成功");
        ok.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(ok);
    }

    /**
     * 用户管理：简单返回用户列表（只读）
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        User admin = requireAdmin(sessionId);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("仅管理员可访问此接口"));
        }

        List<UserSummary> users = userRepository.findAll()
                .stream()
                .map(u -> {
                    UserSummary dto = new UserSummary();
                    dto.setId(u.getId());
                    dto.setUsername(u.getUsername());
                    dto.setDisplayName(u.getDisplayName());
                    dto.setAvatarUrl(u.getAvatarUrl());
                    dto.setEmail(u.getEmail());
                    dto.setCreatedAt(u.getCreatedAt());
                    dto.setUpdatedAt(u.getUpdatedAt());
                    return dto;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(users);
    }

    // ============ 辅助方法 & DTO =============

    /**
     * 将配置更新请求映射为 system_setting 键值对。
     * 供 /api/admin/config 与首次运行向导 /api/admin/wizard 共用，
     * 保证两个入口写入的 key 完全一致。
     *
     * @throws IllegalArgumentException assistants 序列化失败时抛出
     */
    static Map<String, String> toSettingsUpdates(AdminConfigUpdateRequest request,
                                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Map<String, String> updates = new HashMap<>();

        if (request.getExternal() != null) {
            ExternalServicesConfig ext = request.getExternal();
            if (ext.getGoogle() != null) {
                updates.put(KEY_GOOGLE_API_KEY, safe(ext.getGoogle().getApiKey()));
                updates.put(KEY_GOOGLE_MODEL_NAME, safe(ext.getGoogle().getModelName()));
                updates.put(KEY_GOOGLE_API_BASE_URL, safe(ext.getGoogle().getApiBaseUrl()));
            }
            if (ext.getOpenRouter() != null) {
                updates.put(KEY_OPENROUTER_API_KEY, safe(ext.getOpenRouter().getApiKey()));
                updates.put(KEY_OPENROUTER_BASE_URL, safe(ext.getOpenRouter().getBaseUrl()));
            }
            if (ext.getQichacha() != null) {
                updates.put(KEY_QICHACHA_BASE_URL, safe(ext.getQichacha().getBaseUrl()));
                updates.put(KEY_QICHACHA_KEY, safe(ext.getQichacha().getKey()));
                updates.put(KEY_QICHACHA_SECRET, safe(ext.getQichacha().getSecret()));
            }
            if (ext.getTushare() != null) {
                updates.put(KEY_TUSHARE_BASE_URL, safe(ext.getTushare().getBaseUrl()));
                updates.put(KEY_TUSHARE_TOKEN, safe(ext.getTushare().getToken()));
            }
            if (ext.getAliyunOcr() != null) {
                updates.put(KEY_ALIYUN_OCR_ACCESS_KEY_ID, safe(ext.getAliyunOcr().getAccessKeyId()));
                updates.put(KEY_ALIYUN_OCR_ACCESS_KEY_SECRET, safe(ext.getAliyunOcr().getAccessKeySecret()));
                updates.put(KEY_ALIYUN_OCR_ENDPOINT, safe(ext.getAliyunOcr().getEndpoint()));
                updates.put(KEY_ALIYUN_OCR_REGION_ID, safe(ext.getAliyunOcr().getRegionId()));
                updates.put(KEY_ALIYUN_OCR_PUBLIC_BASE_URL, safe(ext.getAliyunOcr().getPublicBaseUrl()));
            }
            if (ext.getPkulaw() != null) {
                updates.put(KEY_PKULAW_TOKEN, safe(ext.getPkulaw().getToken()));
            }
            if (ext.getBocha() != null) {
                updates.put(KEY_BOCHA_API_KEY, safe(ext.getBocha().getApiKey()));
            }
            if (ext.getElevenLabs() != null) {
                updates.put(KEY_ELEVENLABS_API_KEY, safe(ext.getElevenLabs().getApiKey()));
                updates.put(KEY_ELEVENLABS_BASE_URL, safe(ext.getElevenLabs().getBaseUrl()));
                updates.put(KEY_ELEVENLABS_MODEL_ID, safe(ext.getElevenLabs().getModelId()));
                updates.put(KEY_ELEVENLABS_DEFAULT_VOICE_ID, safe(ext.getElevenLabs().getDefaultVoiceId()));
            }
        }

        if (request.getAi() != null) {
            AiConfig ai = request.getAi();
            if (ai.getSystemPromptOllama() != null) {
                updates.put(KEY_AI_SYSTEM_PROMPT_OLLAMA, ai.getSystemPromptOllama());
            }
            if (ai.getSystemPromptGemini() != null) {
                updates.put(KEY_AI_SYSTEM_PROMPT_GEMINI, ai.getSystemPromptGemini());
            }
            if (ai.getActiveProvider() != null) {
                updates.put(KEY_AI_ACTIVE_PROVIDER, ai.getActiveProvider());
            }
            if (ai.getAssistants() != null) {
                try {
                    String json = objectMapper.writeValueAsString(ai.getAssistants());
                    updates.put(KEY_AI_ASSISTANTS, json);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Assistants JSON serialization failed", e);
                }
            }
        }

        return updates;
    }

    private User requireAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .filter(adminAccessService::isAdmin)
                .orElse(null);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }

    private static String safe(String v) {
        // 统一 trim，避免用户粘贴 key/secret 时带入换行/空格导致签名不匹配
        return v == null ? "" : v.trim();
    }

    // -------- DTO 定义 --------

    public static class AdminConfigResponse {
        private ExternalServicesConfig external;
        private AiConfig ai;

        public ExternalServicesConfig getExternal() { return external; }
        public void setExternal(ExternalServicesConfig external) { this.external = external; }
        public AiConfig getAi() { return ai; }
        public void setAi(AiConfig ai) { this.ai = ai; }
    }

    public static class AdminConfigUpdateRequest {
        private ExternalServicesConfig external;
        private AiConfig ai;

        public ExternalServicesConfig getExternal() { return external; }
        public void setExternal(ExternalServicesConfig external) { this.external = external; }
        public AiConfig getAi() { return ai; }
        public void setAi(AiConfig ai) { this.ai = ai; }
    }

    public static class ExternalServicesConfig {
        private GoogleConfig google;
        private OpenRouterConfig openRouter;
        private QichachaConfig qichacha;
        private TushareConfig tushare;
        private AliyunOcrConfig aliyunOcr;
        private PkulawConfig pkulaw;
        private BochaConfig bocha;
        private ElevenLabsConfig elevenLabs;

        public GoogleConfig getGoogle() { return google; }
        public void setGoogle(GoogleConfig google) { this.google = google; }
        public OpenRouterConfig getOpenRouter() { return openRouter; }
        public void setOpenRouter(OpenRouterConfig openRouter) { this.openRouter = openRouter; }
        public QichachaConfig getQichacha() { return qichacha; }
        public void setQichacha(QichachaConfig qichacha) { this.qichacha = qichacha; }
        public TushareConfig getTushare() { return tushare; }
        public void setTushare(TushareConfig tushare) { this.tushare = tushare; }
        public AliyunOcrConfig getAliyunOcr() { return aliyunOcr; }
        public void setAliyunOcr(AliyunOcrConfig aliyunOcr) { this.aliyunOcr = aliyunOcr; }
        public PkulawConfig getPkulaw() { return pkulaw; }
        public void setPkulaw(PkulawConfig pkulaw) { this.pkulaw = pkulaw; }
        public BochaConfig getBocha() { return bocha; }
        public void setBocha(BochaConfig bocha) { this.bocha = bocha; }
        public ElevenLabsConfig getElevenLabs() { return elevenLabs; }
        public void setElevenLabs(ElevenLabsConfig elevenLabs) { this.elevenLabs = elevenLabs; }
    }

    public static class GoogleConfig {
        private String apiKey;
        private String modelName;
        private String apiBaseUrl;

        public GoogleConfig() {}

        public GoogleConfig(String apiKey, String modelName, String apiBaseUrl) {
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    }

    public static class QichachaConfig {
        private String baseUrl;
        private String key;
        private String secret;

        public QichachaConfig() {}

        public QichachaConfig(String baseUrl, String key, String secret) {
            this.baseUrl = baseUrl;
            this.key = key;
            this.secret = secret;
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    public static class TushareConfig {
        private String baseUrl;
        private String token;

        public TushareConfig() {}

        public TushareConfig(String baseUrl, String token) {
            this.baseUrl = baseUrl;
            this.token = token;
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class AliyunOcrConfig {
        private String accessKeyId;
        private String accessKeySecret;
        private String endpoint;
        private String regionId;
        private String publicBaseUrl;

        public AliyunOcrConfig() {}

        public AliyunOcrConfig(String accessKeyId, String accessKeySecret, String endpoint, String regionId, String publicBaseUrl) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.endpoint = endpoint;
            this.regionId = regionId;
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegionId() { return regionId; }
        public void setRegionId(String regionId) { this.regionId = regionId; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    }

    public static class PkulawConfig {
        private String token;
        public PkulawConfig() {}
        public PkulawConfig(String token) { this.token = token; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class BochaConfig {
        private String apiKey;
        public BochaConfig() {}
        public BochaConfig(String apiKey) { this.apiKey = apiKey; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static class ElevenLabsConfig {
        private String apiKey;
        private String baseUrl;
        private String modelId;
        private String defaultVoiceId;

        public ElevenLabsConfig() {}
        public ElevenLabsConfig(String apiKey, String baseUrl, String modelId, String defaultVoiceId) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelId = modelId;
            this.defaultVoiceId = defaultVoiceId;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getDefaultVoiceId() { return defaultVoiceId; }
        public void setDefaultVoiceId(String defaultVoiceId) { this.defaultVoiceId = defaultVoiceId; }
    }

    public static class OpenRouterConfig {
        private String apiKey;
        private String baseUrl;
        
        public OpenRouterConfig() {}
        public OpenRouterConfig(String apiKey, String baseUrl) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class AiConfig {
        private String activeProvider;
        private String systemPromptOllama;
        private String systemPromptGemini;
        private List<com.checkba.model.ai.AiAssistantConfig> assistants;

        public String getActiveProvider() { return activeProvider; }
        public void setActiveProvider(String activeProvider) { this.activeProvider = activeProvider; }
        public String getSystemPromptOllama() { return systemPromptOllama; }
        public void setSystemPromptOllama(String systemPromptOllama) { this.systemPromptOllama = systemPromptOllama; }
        public String getSystemPromptGemini() { return systemPromptGemini; }
        public void setSystemPromptGemini(String systemPromptGemini) { this.systemPromptGemini = systemPromptGemini; }
        public List<com.checkba.model.ai.AiAssistantConfig> getAssistants() { return assistants; }
        public void setAssistants(List<com.checkba.model.ai.AiAssistantConfig> assistants) { this.assistants = assistants; }
    }

    public static class UserSummary {
        private Long id;
        private String username;
        private String displayName;
        private String avatarUrl;
        private String email;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}


