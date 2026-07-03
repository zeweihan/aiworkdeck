package com.checkba.service.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.checkba.config.AiModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini Context Cache 管理服务。
 *
 * 通过内容哈希做内存级缓存，避免短期内重复上传相同内容；
 * 缓存条目的 TTL 由 Gemini 侧管理（默认 1 小时）。
 *
 * 历史背景：原为 AiChatController 私有方法 getOrCreateGeminiCache + 控制器字段
 * activeGeminiCaches，Phase 2 下沉为专职服务。
 */
@Service
public class GeminiCacheService {

    private static final Logger log = LoggerFactory.getLogger(GeminiCacheService.class);

    private final AiModelProperties aiModelProperties;

    // Cache for Gemini Cache IDs: Map<ContentHash, CacheName>
    private final Map<String, String> activeGeminiCaches = new ConcurrentHashMap<>();

    public GeminiCacheService(AiModelProperties aiModelProperties) {
        this.aiModelProperties = aiModelProperties;
    }

    public String getOrCreateGeminiCache(String content) {
        // Simple hash content to key
        String hash = cn.hutool.crypto.digest.DigestUtil.md5Hex(content);
        if (activeGeminiCaches.containsKey(hash)) {
            // Validate validity? For now assume valid until TTL (default 1h). We can store timestamp.
            return activeGeminiCaches.get(hash);
        }

        // Create Cache via REST
        AiModelProperties.Gemini geminiCfg = aiModelProperties.getGemini();
        if (geminiCfg.getApiKey() == null) throw new RuntimeException("No API Key");

        String url = geminiCfg.getApiBaseUrl() + "/cachedContents?key=" + geminiCfg.getApiKey();

        JSONObject payload = new JSONObject();
        payload.set("model", "models/" + geminiCfg.getModelName());

        JSONObject contentObj = new JSONObject();
        contentObj.set("role", "user");
        JSONArray parts = new JSONArray();
        JSONObject part = new JSONObject();
        part.set("text", content);
        parts.add(part);
        contentObj.set("parts", parts);

        payload.set("contents", java.util.Collections.singletonList(contentObj));
        // payload.set("ttl", "600s"); // default 1h is fine

        String resp = cn.hutool.http.HttpRequest.post(url)
                .body(payload.toString())
                .execute()
                .body();

        JSONObject json = cn.hutool.json.JSONUtil.parseObj(resp);
        if (json.containsKey("name")) {
            String cacheName = json.getStr("name");
            activeGeminiCaches.put(hash, cacheName);
            return cacheName;
        } else {
            throw new RuntimeException("Failed to create cache: " + resp);
        }
    }
}
