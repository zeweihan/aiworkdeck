package com.checkba.service.ai;

import com.checkba.model.ai.AiAssistantConfig;
import com.checkba.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手配置服务：从系统设置动态加载助手配置（ai.assistants）。
 *
 * 历史背景：原为 AiChatController 的 loadAssistants/getAssistant 私有方法
 * 与 assistantCache 字段，Phase 2 下沉为专职服务。
 *
 * getAssistant/assistantCache 随 v1 同步端点（POST /api/ai/chat）一起移除：
 * 唯一调用方是已删除的 AiChatService。顺带消掉一个多租户隐患——那个缓存键只有
 * modelId 加 assistantId、不含密钥指纹（对比 ChatModelFactory 刻意带了
 * keyFingerprint()），server 模式下会让后来的用户复用别人平台 key 建出的实例。
 * 若将来要再引入按模型缓存的助手实例，缓存键必须带上密钥指纹。
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private static final String KEY_AI_ASSISTANTS = "ai.assistants";

    private final SystemSettingService systemSettingService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AiAssistantService(SystemSettingService systemSettingService,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 从系统设置加载助手配置（保留插入顺序）。
     */
    public Map<String, AiAssistantConfig> loadAssistants() {
        Map<String, AiAssistantConfig> map = new LinkedHashMap<>(); // Preserve order
        String json = systemSettingService.get(KEY_AI_ASSISTANTS, null);
        if (json != null && !json.isBlank()) {
            try {
                List<AiAssistantConfig> list = objectMapper.readValue(json,
                        new com.fasterxml.jackson.core.type.TypeReference<List<AiAssistantConfig>>() {});
                for (AiAssistantConfig cfg : list) {
                    map.put(cfg.getId(), cfg);
                }
            } catch (Exception e) {
                log.error("Failed to parse assistants config", e);
            }
        }
        return map;
    }
}
