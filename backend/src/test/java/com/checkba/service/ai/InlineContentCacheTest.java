package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 内联正文缓存（Office 插件「正文省传」的后端半边）。
 */
class InlineContentCacheTest {

    @Test
    @DisplayName("存取按会话隔离：哈希一致才取得到，跨会话取不到")
    void getRequiresMatchingHashAndConversation() {
        InlineContentCache cache = new InlineContentCache();
        cache.put("conv-a", "甲方乙方");

        String hash = InlineContentCache.sha256Hex("甲方乙方");
        assertEquals("甲方乙方", cache.get("conv-a", hash), "同会话同哈希应命中");
        assertNull(cache.get("conv-b", hash), "别的会话不该取到本会话的正文");
        assertNull(cache.get("conv-a", InlineContentCache.sha256Hex("改过的正文")), "哈希对不上应未命中");
        assertNull(cache.get("conv-a", null), "无哈希即无省传信号");
    }

    @Test
    @DisplayName("同一会话再次 put 覆盖旧正文：文档改动后旧哈希立即失效")
    void putOverwritesPreviousContent() {
        InlineContentCache cache = new InlineContentCache();
        cache.put("conv-a", "第一版");
        cache.put("conv-a", "第二版");

        assertNull(cache.get("conv-a", InlineContentCache.sha256Hex("第一版")), "旧哈希应失效");
        assertEquals("第二版", cache.get("conv-a", InlineContentCache.sha256Hex("第二版")), "新哈希应命中");
    }

    @Test
    @DisplayName("超过条目上限按 LRU 驱逐最久未用的会话（内存上界 32 × 200k 字符）")
    void evictsLeastRecentlyUsedBeyondLimit() {
        InlineContentCache cache = new InlineContentCache();
        for (int i = 0; i < InlineContentCache.MAX_ENTRIES; i++) {
            cache.put("conv-" + i, "正文" + i);
        }
        // 触碰最早的一条，使其成为最近使用
        assertEquals("正文0", cache.get("conv-0", InlineContentCache.sha256Hex("正文0")));

        cache.put("conv-new", "新会话正文");

        assertEquals("正文0", cache.get("conv-0", InlineContentCache.sha256Hex("正文0")),
                "刚被访问过的会话不该被驱逐");
        assertNull(cache.get("conv-1", InlineContentCache.sha256Hex("正文1")),
                "最久未用的会话应被驱逐，未命中即降级为无正文");
    }
}
