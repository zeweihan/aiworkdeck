package com.checkba.service.entitlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 功能目录是**跨仓契约**：官网 doc/desktop-contract.md「权益命名」一节按同一张表
 * 校验兑换码白名单，名字对不上就是发出去的码兑不了、买了的权益桌面端不认。
 * 改这里的字面量前先确认官网侧同步。
 */
class FeatureCatalogTest {

    @Test
    @DisplayName("feature 名字面量锁定（与官网契约逐字一致）")
    void featureNamesAreFrozen() {
        assertEquals("app.unlocked", FeatureCatalog.APP_UNLOCKED);
        assertEquals("clipboard.unlimited", FeatureCatalog.CLIPBOARD_UNLIMITED);
        assertEquals("stage.unlimited", FeatureCatalog.STAGE_UNLIMITED);
        assertEquals("plan.pro", FeatureCatalog.PLAN_PRO);
    }

    @Test
    @DisplayName("目录内容与顺序锁定")
    void catalogContents() {
        assertEquals(
                List.of("app.unlocked", "clipboard.unlimited", "stage.unlimited", "plan.pro"),
                List.copyOf(FeatureCatalog.all()));
    }

    @Test
    @DisplayName("每项都有中文展示名；未知 feature 原样返回")
    void displayNames() {
        for (String feature : FeatureCatalog.all()) {
            assertNotEquals(feature, FeatureCatalog.displayName(feature),
                    feature + " 缺中文展示名");
        }
        // 付费 Skill / 插件是动态权益，不在目录里
        assertFalse(FeatureCatalog.isKnown("skill:due-diligence"));
        assertEquals("skill:due-diligence", FeatureCatalog.displayName("skill:due-diligence"));
    }
}
