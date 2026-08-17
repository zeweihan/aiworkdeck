package com.checkba.controller;

import com.checkba.config.AiModelProperties;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
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
 * - AI 服务激活的供应商（三档：AWD_CLOUD / OPENROUTER / OLLAMA）
 * - AI 模型选择（默认 / 辅助 / 子 Agent）、网络区域、本地 Ollama 的地址与模型名
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

    // 会议转写默认值（通义听悟 + OSS，与 MeetingTranscriptionService 同源）
    @Value("${meeting.asr.access-key-id:}")
    private String defaultMeetingAccessKeyId;

    @Value("${meeting.asr.access-key-secret:}")
    private String defaultMeetingAccessKeySecret;

    @Value("${meeting.asr.app-key:}")
    private String defaultMeetingAppKey;

    @Value("${meeting.oss.bucket:}")
    private String defaultMeetingOssBucket;

    @Value("${meeting.oss.endpoint:}")
    private String defaultMeetingOssEndpoint;

    // OpenRouter 默认值
    @Value("${ai.model.open-router.api-key:}")
    private String defaultOpenRouterApiKey;

    @Value("${ai.model.open-router.base-url:https://openrouter.ai/api/v1}")
    private String defaultOpenRouterBaseUrl;

    // === 配置 key 常量 ===
    // AI
    private static final String KEY_AI_ACTIVE_PROVIDER = "ai.activeProvider";
    /**
     * 跨境传输的单独同意（《个人信息保护法》第三十九条）。
     *
     * 值为 ISO 时间戳，空 = 未同意。平台 AI 通道把内容直接发往境外的 OpenRouter，
     * 属「向境外提供个人信息」，需在**告知后单独取得**同意——不能与服务条款一揽子打包，
     * 也不能预先勾选。同意点刻意放在「把供应商切成 AWD_CLOUD」这一刻：那正是内容
     * 开始出境的决定点，既不打断新用户上手，又在做决定时就在眼前。
     *
     * 一并记下同意时看到的文本版本，日后改了告知内容才知道谁同意的是哪一版。
     */
    private static final String KEY_AI_CROSS_BORDER_CONSENT_AT = "ai.crossBorder.consentAt";
    private static final String KEY_AI_CROSS_BORDER_CONSENT_VERSION = "ai.crossBorder.consentVersion";
    /** 当前告知文本的版本号；改了跨境告知的实质内容就要 +1，让旧同意失效并重新征求 */
    private static final String CROSS_BORDER_NOTICE_VERSION = "2026-08-08";
    // ai.systemPrompt.OLLAMA / ai.systemPrompt.GEMINI 两个键已随 v1 /api/ai/chat 一起移除：
    // 唯一读者是已删的 AiChatService，且它按模型名字符串而非 provider 选 key，
    // 对四条通道本来就全部失效。今天真正生效的 system prompt 由 ContextAssemblerService 拼装，
    // provider 无关、admin 无入口。
    private static final String KEY_AI_ASSISTANTS = "ai.assistants";
    // 三个模型选择键：留空一律表示「跟随内置默认」（工厂侧空白视为未配置，回退 yml）。
    // ai.subagentModel 留空是「继承 ai.auxModel」，不是「继承主会话模型」。
    private static final String KEY_AI_DEFAULT_MODEL = "ai.defaultModel";
    private static final String KEY_AI_AUX_MODEL = "ai.auxModel";
    private static final String KEY_AI_SUBAGENT_MODEL = "ai.subagentModel";
    // 网络区域手动覆盖（auto | domestic | international）。本地判定对出差/挂代理/
    // 公司专线出境的用户必然判错，这个开关是唯一出路，属一等设置不是隐藏兜底。
    private static final String KEY_AI_NETWORK_REGION = com.checkba.service.ai.NetworkRegionService.SETTING_KEY;
    // 本地 Ollama（离线/实验档）的地址与模型名。改造前只能靠 AI_MODEL_OLLAMA_MODEL_NAME
    // 环境变量覆盖 yml 里的硬编码字面量，终端用户等于改不了。
    private static final String KEY_AI_OLLAMA_BASE_URL = "ai.ollama.baseUrl";
    private static final String KEY_AI_OLLAMA_MODEL_NAME = "ai.ollama.modelName";
    // 已废弃：ai.systemPrompt.OLLAMA / ai.systemPrompt.GEMINI 两个键随 v1 同步对话通道
    // （AiChatService）一起删除。那两个 tab 在删除之前就已对全部通道失效——它按模型名
    // 字符串而非 provider 选 key；今天真正生效的 system prompt 由 ContextAssemblerService
    // 拼装、provider 无关、admin 没有入口（要给 admin 真入口是另一件事）。

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

    // 会议转写（键名与 MeetingTranscriptionService.KEY_* 一致，勿改单侧）
    private static final String KEY_MEETING_ACCESS_KEY_ID = com.checkba.service.meeting.MeetingTranscriptionService.KEY_ACCESS_KEY_ID;
    private static final String KEY_MEETING_ACCESS_KEY_SECRET = com.checkba.service.meeting.MeetingTranscriptionService.KEY_ACCESS_KEY_SECRET;
    private static final String KEY_MEETING_APP_KEY = com.checkba.service.meeting.MeetingTranscriptionService.KEY_APP_KEY;
    private static final String KEY_MEETING_OSS_BUCKET = com.checkba.service.meeting.MeetingTranscriptionService.KEY_OSS_BUCKET;
    private static final String KEY_MEETING_OSS_ENDPOINT = com.checkba.service.meeting.MeetingTranscriptionService.KEY_OSS_ENDPOINT;

    // OpenRouter
    private static final String KEY_OPENROUTER_API_KEY = "external.openrouter.apiKey";
    private static final String KEY_OPENROUTER_BASE_URL = "external.openrouter.baseUrl";

    // ============ 配置读取 =============

    @GetMapping("/config")
    public ResponseEntity<?> getAdminConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        User admin = requireAdmin(sessionId);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(LangText.of("仅管理员可访问此接口", "Admin access only")));
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

        defaults.put(KEY_MEETING_ACCESS_KEY_ID, defaultMeetingAccessKeyId);
        defaults.put(KEY_MEETING_ACCESS_KEY_SECRET, defaultMeetingAccessKeySecret);
        defaults.put(KEY_MEETING_APP_KEY, defaultMeetingAppKey);
        defaults.put(KEY_MEETING_OSS_BUCKET, defaultMeetingOssBucket);
        defaults.put(KEY_MEETING_OSS_ENDPOINT, defaultMeetingOssEndpoint);

        // OpenRouter
        defaults.put(KEY_OPENROUTER_API_KEY, defaultOpenRouterApiKey);
        defaults.put(KEY_OPENROUTER_BASE_URL, defaultOpenRouterBaseUrl);

        // AI 默认值
        defaults.put(KEY_AI_ACTIVE_PROVIDER,
                aiModelProperties.getProvider() != null
                        ? aiModelProperties.getProvider().name()
                        : AiModelProperties.Provider.OLLAMA.name());
        // 三个模型键的默认值刻意是空串：空 = 跟随内置默认（defaultModel 回退 yml、
        // subagentModel 回退辅助模型）。这里回填 yml 的具体模型 id 会让「跟随默认」
        // 这个选项在设置页保存一次之后永久消失。
        defaults.put(KEY_AI_DEFAULT_MODEL, "");
        defaults.put(KEY_AI_AUX_MODEL, "");
        defaults.put(KEY_AI_SUBAGENT_MODEL, "");
        defaults.put(KEY_AI_NETWORK_REGION, com.checkba.service.ai.NetworkRegionService.MODE_AUTO);
        // Ollama 的两项相反：回填当前真正生效的值（yml 或 AI_MODEL_OLLAMA_* 环境变量），
        // 否则设置页会显示空输入框，用户不知道现在连的是哪个地址、拉的是哪个模型。
        defaults.put(KEY_AI_OLLAMA_BASE_URL, aiModelProperties.getOllama().getBaseUrl());
        defaults.put(KEY_AI_OLLAMA_MODEL_NAME, aiModelProperties.getOllama().getModelName());

        // 当前存储值（DB > 默认值）
        Map<String, String> all = systemSettingService.getMany(defaults);

        AdminConfigResponse resp = new AdminConfigResponse();

        // 外部服务
        ExternalServicesConfig external = new ExternalServicesConfig();
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
        external.setTingwu(new TingwuConfig(
                all.get(KEY_MEETING_ACCESS_KEY_ID),
                all.get(KEY_MEETING_ACCESS_KEY_SECRET),
                all.get(KEY_MEETING_APP_KEY),
                all.get(KEY_MEETING_OSS_BUCKET),
                all.get(KEY_MEETING_OSS_ENDPOINT)
        ));
        resp.setExternal(external);

        // AI 配置
        AiConfig ai = new AiConfig();
        String activeProvider = all.get(KEY_AI_ACTIVE_PROVIDER);
        ai.setActiveProvider(activeProvider);
        ai.setDefaultModel(all.get(KEY_AI_DEFAULT_MODEL));
        ai.setAuxModel(all.get(KEY_AI_AUX_MODEL));
        ai.setSubagentModel(all.get(KEY_AI_SUBAGENT_MODEL));
        ai.setNetworkRegion(all.get(KEY_AI_NETWORK_REGION));
        ai.setOllamaBaseUrl(all.get(KEY_AI_OLLAMA_BASE_URL));
        ai.setOllamaModelName(all.get(KEY_AI_OLLAMA_MODEL_NAME));

        // 同意只有在版本一致时才算数：告知文本改过，旧同意作废、需重新征求
        String consentVersion = systemSettingService.get(KEY_AI_CROSS_BORDER_CONSENT_VERSION, "");
        ai.setCrossBorderConsentAt(
                CROSS_BORDER_NOTICE_VERSION.equals(consentVersion)
                        ? systemSettingService.get(KEY_AI_CROSS_BORDER_CONSENT_AT, "")
                        : "");
        ai.setCrossBorderNoticeVersion(CROSS_BORDER_NOTICE_VERSION);


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
                    .body(error(LangText.of("仅管理员可访问此接口", "Admin access only")));
        }

        // 跨境同意闸门要在写库之前把关：平台通道会把内容直接发往境外的模型服务商，
        // 属「向境外提供个人信息」，需单独同意（个保法第三十九条）。
        // 判定与文案在 crossBorderBlockReason，与首启向导共用同一处定义。
        String crossBorderBlock = crossBorderBlockReason(request.getAi(), systemSettingService);
        if (crossBorderBlock != null) {
            return ResponseEntity.badRequest().body(error(crossBorderBlock));
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
        ok.put("message", LangText.of("保存成功", "Saved"));
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
                    .body(error(LangText.of("仅管理员可访问此接口", "Admin access only")));
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
     * <p><b>null 字段一律跳过，不再写空串</b>（{@link #putIfPresent}）。原来同组里只要有一个
     * 字段有值，其余字段会被 {@code safe(null)} 变成空串落库；而 {@code SystemSettingService}
     * 的读取（{@code get} / {@code getMany}）只在**行不存在**时回退默认值，
     * 「行存在但值为空」返回的就是空串，于是 baseUrl 被清空后 QichachaService 的 url 变成
     * {@code /ECIInfoVerify/GetInfo}、TushareService 往空串 post。
     * 两种真正从可用变不可用的场景：baseUrl/secret 由环境变量提供的部署；管理员走
     * {@code /api/admin/wizard/reset} 重跑向导，只填一个 key 就把原本正确的 baseUrl 清空。
     *
     * <p><b>语义变化</b>：admin 页是整表回传（每个字段都带着当前值），因此不受影响；但
     * 「把某个字段清空」这个操作从此需要显式空串（前端传 {@code ""} 而不是不传/传 null）。
     * 这是刻意的取舍——静默清掉别人的配置比少一个清空动作危险得多。
     *
     * @throws IllegalArgumentException assistants 序列化失败、模型 id 不在白名单、
     *                                  或网络区域取值非法时抛出（调用方转 400）
     */
    static Map<String, String> toSettingsUpdates(AdminConfigUpdateRequest request,
                                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Map<String, String> updates = new HashMap<>();

        if (request.getExternal() != null) {
            ExternalServicesConfig ext = request.getExternal();
            if (ext.getOpenRouter() != null) {
                putIfPresent(updates, KEY_OPENROUTER_API_KEY, ext.getOpenRouter().getApiKey());
                putIfPresent(updates, KEY_OPENROUTER_BASE_URL, ext.getOpenRouter().getBaseUrl());
            }
            if (ext.getQichacha() != null) {
                putIfPresent(updates, KEY_QICHACHA_BASE_URL, ext.getQichacha().getBaseUrl());
                putIfPresent(updates, KEY_QICHACHA_KEY, ext.getQichacha().getKey());
                putIfPresent(updates, KEY_QICHACHA_SECRET, ext.getQichacha().getSecret());
            }
            if (ext.getTushare() != null) {
                putIfPresent(updates, KEY_TUSHARE_BASE_URL, ext.getTushare().getBaseUrl());
                putIfPresent(updates, KEY_TUSHARE_TOKEN, ext.getTushare().getToken());
            }
            if (ext.getAliyunOcr() != null) {
                putIfPresent(updates, KEY_ALIYUN_OCR_ACCESS_KEY_ID, ext.getAliyunOcr().getAccessKeyId());
                putIfPresent(updates, KEY_ALIYUN_OCR_ACCESS_KEY_SECRET, ext.getAliyunOcr().getAccessKeySecret());
                putIfPresent(updates, KEY_ALIYUN_OCR_ENDPOINT, ext.getAliyunOcr().getEndpoint());
                putIfPresent(updates, KEY_ALIYUN_OCR_REGION_ID, ext.getAliyunOcr().getRegionId());
                putIfPresent(updates, KEY_ALIYUN_OCR_PUBLIC_BASE_URL, ext.getAliyunOcr().getPublicBaseUrl());
            }
            if (ext.getPkulaw() != null) {
                putIfPresent(updates, KEY_PKULAW_TOKEN, ext.getPkulaw().getToken());
            }
            if (ext.getBocha() != null) {
                putIfPresent(updates, KEY_BOCHA_API_KEY, ext.getBocha().getApiKey());
            }
            if (ext.getTingwu() != null) {
                putIfPresent(updates, KEY_MEETING_ACCESS_KEY_ID, ext.getTingwu().getAccessKeyId());
                putIfPresent(updates, KEY_MEETING_ACCESS_KEY_SECRET, ext.getTingwu().getAccessKeySecret());
                putIfPresent(updates, KEY_MEETING_APP_KEY, ext.getTingwu().getAppKey());
                putIfPresent(updates, KEY_MEETING_OSS_BUCKET, ext.getTingwu().getOssBucket());
                putIfPresent(updates, KEY_MEETING_OSS_ENDPOINT, ext.getTingwu().getOssEndpoint());
            }
        }

        if (request.getAi() != null) {
            AiConfig ai = request.getAi();
            // 本次勾选了同意就先记下（含文本版本），再判断能不能切到平台通道
            if (Boolean.TRUE.equals(ai.getCrossBorderConsent())) {
                updates.put(KEY_AI_CROSS_BORDER_CONSENT_AT, java.time.Instant.now().toString());
                updates.put(KEY_AI_CROSS_BORDER_CONSENT_VERSION, CROSS_BORDER_NOTICE_VERSION);
            } else if (Boolean.FALSE.equals(ai.getCrossBorderConsent())) {
                // 撤回同意：个保法第十五条给的权利，撤回后不得再走平台通道
                updates.put(KEY_AI_CROSS_BORDER_CONSENT_AT, "");
                updates.put(KEY_AI_CROSS_BORDER_CONSENT_VERSION, "");
            }
            if (ai.getActiveProvider() != null) {
                // 供应商收敛成三档后要挡住旧客户端/手工请求写回已下线的档位（如 GEMINI）：
                // 启动期迁移只在启动时跑一次，运行期写进去的坏值会一直生效到下次重启，
                // 而 resolveProvider() 对认不出的值只 warn 一句就静默回落 yml。
                String provider = ai.getActiveProvider().trim().toUpperCase(java.util.Locale.ROOT);
                try {
                    AiModelProperties.Provider.valueOf(provider);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(LangText.of(
                            "AI 提供商取值非法：" + ai.getActiveProvider()
                                    + "（只接受 AWD_CLOUD / OPENROUTER / OLLAMA）",
                            "Invalid AI provider value: " + ai.getActiveProvider()
                                    + " (only AWD_CLOUD / OPENROUTER / OLLAMA are accepted)"));
                }
                updates.put(KEY_AI_ACTIVE_PROVIDER, provider);
            }
            // 三个模型选择键：空串是合法值（= 跟随内置默认），非空必须在白名单内。
            // 不校验的话一个手改/陈旧的 id 会被工厂静默回落默认模型，
            // 设置页显示的与实际发出去的模型不一致——正是本次改造要修的老毛病。
            putModelSetting(updates, KEY_AI_DEFAULT_MODEL, ai.getDefaultModel(), LangText.of("默认模型", "Default model"));
            putModelSetting(updates, KEY_AI_AUX_MODEL, ai.getAuxModel(), LangText.of("辅助模型", "Auxiliary model"));
            putModelSetting(updates, KEY_AI_SUBAGENT_MODEL, ai.getSubagentModel(), LangText.of("子 Agent 模型", "Sub-agent model"));
            if (ai.getNetworkRegion() != null) {
                String region = ai.getNetworkRegion().trim().toLowerCase(java.util.Locale.ROOT);
                boolean legal = region.isEmpty()
                        || com.checkba.service.ai.NetworkRegionService.MODE_AUTO.equals(region)
                        || com.checkba.service.ai.NetworkRegionService.MODE_DOMESTIC.equals(region)
                        || com.checkba.service.ai.NetworkRegionService.MODE_INTERNATIONAL.equals(region);
                if (!legal) {
                    throw new IllegalArgumentException(LangText.of(
                            "网络区域取值非法：" + ai.getNetworkRegion()
                                    + "（只接受 auto / domestic / international）",
                            "Invalid network region value: " + ai.getNetworkRegion()
                                    + " (only auto / domestic / international are accepted)"));
                }
                updates.put(KEY_AI_NETWORK_REGION, region.isEmpty()
                        ? com.checkba.service.ai.NetworkRegionService.MODE_AUTO : region);
            }
            // Ollama 的地址与模型名是自由文本：本地模型名不在白名单内（白名单是 OpenRouter 的目录）
            putIfPresent(updates, KEY_AI_OLLAMA_BASE_URL, ai.getOllamaBaseUrl());
            putIfPresent(updates, KEY_AI_OLLAMA_MODEL_NAME, ai.getOllamaModelName());
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

    /**
     * 只在字段真的被提交时写入（null = 本次请求没带这个字段，保持库里原值不动）。
     * 空串仍然会写——那是「显式清空」，见 {@link #toSettingsUpdates} 的语义说明。
     */
    private static void putIfPresent(Map<String, String> updates, String key, String value) {
        if (value == null) {
            return;
        }
        updates.put(key, safe(value));
    }

    /**
     * 模型选择键的写入：空串放行（跟随内置默认），非空必须在 {@link com.checkba.service.ai.AllowedModels}
     * 白名单内，否则报 400 而不是让工厂静默回落。
     */
    private static void putModelSetting(Map<String, String> updates, String key, String value, String label) {
        if (value == null) {
            return;
        }
        String model = value.trim();
        if (!model.isEmpty() && !com.checkba.service.ai.AllowedModels.isAllowed(model)) {
            throw new IllegalArgumentException(label + LangText.of(
                    "「" + model + "」不在可用模型清单内，从设置页的模型下拉中重新选一个（留空表示跟随内置默认）",
                    " \"" + model + "\" is not in the available models list. Pick one from the model dropdown in Settings (leave blank to follow the built-in default)."));
        }
        updates.put(key, model);
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
        private OpenRouterConfig openRouter;
        private QichachaConfig qichacha;
        private TushareConfig tushare;
        private AliyunOcrConfig aliyunOcr;
        private PkulawConfig pkulaw;
        private BochaConfig bocha;
        private TingwuConfig tingwu;

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
        public TingwuConfig getTingwu() { return tingwu; }
        public void setTingwu(TingwuConfig tingwu) { this.tingwu = tingwu; }
    }

    /** 会议转写（通义听悟 + OSS 中转）凭证五件套 */
    public static class TingwuConfig {
        private String accessKeyId;
        private String accessKeySecret;
        private String appKey;
        private String ossBucket;
        private String ossEndpoint;

        public TingwuConfig() {}

        public TingwuConfig(String accessKeyId, String accessKeySecret, String appKey,
                            String ossBucket, String ossEndpoint) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.appKey = appKey;
            this.ossBucket = ossBucket;
            this.ossEndpoint = ossEndpoint;
        }

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getOssBucket() { return ossBucket; }
        public void setOssBucket(String ossBucket) { this.ossBucket = ossBucket; }
        public String getOssEndpoint() { return ossEndpoint; }
        public void setOssEndpoint(String ossEndpoint) { this.ossEndpoint = ossEndpoint; }
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

    /**
     * 是否已就跨境传输取得有效的单独同意：本次请求勾选了，或库里已有同版本的同意记录。
     * 版本不一致按未同意处理——告知文本改过，旧同意覆盖不到新的处理方式。
     */
    private boolean hasCrossBorderConsent(AiConfig ai) {
        return hasCrossBorderConsent(ai, systemSettingService);
    }

    static boolean hasCrossBorderConsent(AiConfig ai, SystemSettingService settings) {
        if (Boolean.TRUE.equals(ai.getCrossBorderConsent())) return true;
        if (Boolean.FALSE.equals(ai.getCrossBorderConsent())) return false;
        String at = settings.get(KEY_AI_CROSS_BORDER_CONSENT_AT, "");
        String version = settings.get(KEY_AI_CROSS_BORDER_CONSENT_VERSION, "");
        return at != null && !at.isBlank() && CROSS_BORDER_NOTICE_VERSION.equals(version);
    }

    /**
     * 切换到平台通道时的跨境同意闸门：可以放行返回 null，否则返回给用户看的中文原因。
     *
     * <p><b>为什么做成静态、供两个入口共用</b>：这道闸原先只在
     * {@link #updateAdminConfig} 里，而首启向导走的是 {@link WizardController#initialize}
     * → 直接调静态的 {@link #toSettingsUpdates}，**完全绕过闸门**。而向导恰恰是用户
     * 选平台通道的主入口（地雷 15：AWD_CLOUD 在向导里恒可选），于是同意对多数用户
     * 形同装饰。合规判断只许有一处定义——两处各写一份，迟早只改了其中一处。
     *
     * <p>文案红线：不得含「登录」「未授权」「请先」——前端 api.js 用这三个子串判掉线并清会话。
     */
    static String crossBorderBlockReason(AiConfig ai, SystemSettingService settings) {
        if (ai == null || ai.getActiveProvider() == null) return null;
        if (!AiModelProperties.Provider.AWD_CLOUD.name()
                .equalsIgnoreCase(ai.getActiveProvider().trim())) {
            return null;
        }
        if (hasCrossBorderConsent(ai, settings)) return null;
        return LangText.of(
                "「AI Workdeck 云端」会把你送入 AI 的内容发往境外的模型服务商处理。"
                        + "勾选跨境传输同意后才能启用；不想让内容出境的话，可以改用本机模型或境内供应商。",
                "\"AI Workdeck Cloud\" sends the content you give the AI to a model provider outside mainland China for processing. "
                        + "Enable it by checking cross-border transfer consent; if you don't want content to leave mainland China, use a local model or a domestic provider instead.");
    }

    public static class AiConfig {
        private String activeProvider;
        /** 空串 = 跟随内置默认（yml 的 ai.model.open-router.default-model）。 */
        private String defaultModel;
        /** 辅助模型：子 Agent / 起标题 / 上下文摘要 / 记忆抽取 / memory_search / 文件自动打标签。 */
        private String auxModel;
        /** 子 Agent 模型；空串 = 继承辅助模型（不是继承主会话模型）。 */
        private String subagentModel;
        /** auto | domestic | international。 */
        private String networkRegion;
        private String ollamaBaseUrl;
        private String ollamaModelName;
        private List<com.checkba.model.ai.AiAssistantConfig> assistants;
        /** 读：已同意的时间戳（空 = 未同意）。写：本次是否勾选了跨境同意 */
        private String crossBorderConsentAt;
        private Boolean crossBorderConsent;
        /** 读：当前告知文本版本，供前端判断是否需要重新征求 */
        private String crossBorderNoticeVersion;

        public String getActiveProvider() { return activeProvider; }
        public void setActiveProvider(String activeProvider) { this.activeProvider = activeProvider; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public String getAuxModel() { return auxModel; }
        public void setAuxModel(String auxModel) { this.auxModel = auxModel; }
        public String getSubagentModel() { return subagentModel; }
        public void setSubagentModel(String subagentModel) { this.subagentModel = subagentModel; }
        public String getNetworkRegion() { return networkRegion; }
        public void setNetworkRegion(String networkRegion) { this.networkRegion = networkRegion; }
        public String getOllamaBaseUrl() { return ollamaBaseUrl; }
        public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }
        public String getOllamaModelName() { return ollamaModelName; }
        public void setOllamaModelName(String ollamaModelName) { this.ollamaModelName = ollamaModelName; }
        public String getCrossBorderConsentAt() { return crossBorderConsentAt; }
        public void setCrossBorderConsentAt(String crossBorderConsentAt) { this.crossBorderConsentAt = crossBorderConsentAt; }
        public Boolean getCrossBorderConsent() { return crossBorderConsent; }
        public void setCrossBorderConsent(Boolean crossBorderConsent) { this.crossBorderConsent = crossBorderConsent; }
        public String getCrossBorderNoticeVersion() { return crossBorderNoticeVersion; }
        public void setCrossBorderNoticeVersion(String crossBorderNoticeVersion) { this.crossBorderNoticeVersion = crossBorderNoticeVersion; }
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


