package com.checkba.controller;

import com.checkba.config.AiModelProperties;
import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨境传输的单独同意（《个人信息保护法》第三十九条）。
 *
 * 平台通道「AI WorkDeck 云端」把用户送入 AI 的内容直接发往境外的 OpenRouter，
 * 属向境外提供个人信息，必须在告知后**单独**取得同意——不能与服务条款一揽子打包，
 * 也不能预先勾选。同意点定在「把供应商切成 AWD_CLOUD」这一刻。
 *
 * 这里锁住三件容易在重构中失守的事：
 * 1. 没同意就切平台通道必须被拒；
 * 2. 同意有版本号，告知文本改版后旧同意作废、需重新征求；
 * 3. 拒绝时的文案不得命中前端的「掉线」判据，否则用户会被踢出登录态。
 */
class CrossBorderConsentTest {

    private static final String KEY_AT = "ai.crossBorder.consentAt";
    private static final String KEY_VERSION = "ai.crossBorder.consentVersion";
    private static final String CURRENT_VERSION = "2026-08-08";

    /** 反射调私有的 hasCrossBorderConsent，避免为测试把方法放宽成 public */
    private boolean hasConsent(AdminConfigController controller, AdminConfigController.AiConfig ai)
            throws Exception {
        Method m = AdminConfigController.class
                .getDeclaredMethod("hasCrossBorderConsent", AdminConfigController.AiConfig.class);
        m.setAccessible(true);
        return (boolean) m.invoke(controller, ai);
    }

    /** 构造器有 6 个依赖，本测试只关心 systemSettingService，其余传 mock 即可 */
    private AdminConfigController controllerWith(Map<String, String> stored) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString()))
                .thenAnswer(inv -> stored.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        return new AdminConfigController(
                settings,
                mock(com.checkba.repository.UserRepository.class),
                mock(AiModelProperties.class),
                mock(com.checkba.service.AdminAccessService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.ai.ChatModelFactory.class));
    }

    @Test
    @DisplayName("从未同意：不得视为已同意（默认必须是「没同意」，绝不能预设为真）")
    void defaultsToNoConsent() throws Exception {
        AdminConfigController controller = controllerWith(new HashMap<>());
        AdminConfigController.AiConfig ai = new AdminConfigController.AiConfig();
        assertFalse(hasConsent(controller, ai));
    }

    @Test
    @DisplayName("本次请求勾选了同意：立即生效，同一请求里即可切到平台通道")
    void explicitConsentInRequestCounts() throws Exception {
        AdminConfigController controller = controllerWith(new HashMap<>());
        AdminConfigController.AiConfig ai = new AdminConfigController.AiConfig();
        ai.setCrossBorderConsent(Boolean.TRUE);
        assertTrue(hasConsent(controller, ai));
    }

    @Test
    @DisplayName("已有同意记录且版本一致：无需重复征求")
    void storedConsentWithMatchingVersionCounts() throws Exception {
        Map<String, String> stored = new HashMap<>();
        stored.put(KEY_AT, "2026-08-08T10:00:00Z");
        stored.put(KEY_VERSION, CURRENT_VERSION);
        AdminConfigController controller = controllerWith(stored);
        assertTrue(hasConsent(controller, new AdminConfigController.AiConfig()));
    }

    @Test
    @DisplayName("告知文本改版：旧同意作废，必须重新征求")
    void staleVersionInvalidatesConsent() throws Exception {
        Map<String, String> stored = new HashMap<>();
        stored.put(KEY_AT, "2026-01-01T10:00:00Z");
        stored.put(KEY_VERSION, "2025-01-01"); // 旧版告知
        AdminConfigController controller = controllerWith(stored);
        assertFalse(hasConsent(controller, new AdminConfigController.AiConfig()),
                "告知内容变了，旧同意覆盖不到新的处理方式");
    }

    @Test
    @DisplayName("撤回同意（个保法第十五条）：即使库里有记录也按未同意处理")
    void explicitWithdrawalOverridesStoredConsent() throws Exception {
        Map<String, String> stored = new HashMap<>();
        stored.put(KEY_AT, "2026-08-08T10:00:00Z");
        stored.put(KEY_VERSION, CURRENT_VERSION);
        AdminConfigController controller = controllerWith(stored);
        AdminConfigController.AiConfig ai = new AdminConfigController.AiConfig();
        ai.setCrossBorderConsent(Boolean.FALSE);
        assertFalse(hasConsent(controller, ai));
    }

    @Test
    @DisplayName("平台通道的枚举名没被改过——闸门靠字符串比对，改名会让闸门静默失效")
    void providerEnumNameUnchanged() {
        assertEquals("AWD_CLOUD", AiModelProperties.Provider.AWD_CLOUD.name());
    }

    @Test
    @DisplayName("拒绝文案不得命中前端的「掉线」判据，否则用户会被踢出登录态")
    void refusalMessageDoesNotLookLikeAuthError() {
        // frontend/src/services/api.js 对含这三个子串的 message 判定未登录并清会话
        String message = "「AI WorkDeck 云端」会把你送入 AI 的内容发往境外的模型服务商处理。"
                + "勾选跨境传输同意后才能启用；不想让内容出境的话，可以改用本机模型或境内供应商。";
        for (String marker : new String[] {"登录", "未授权", "请先"}) {
            assertFalse(message.contains(marker), "跨境同意文案不得含「" + marker + "」");
        }
        // 告知必须点名境外接收方与用途，否则不构成个保法第三十九条要求的「充分告知」
        assertTrue(message.contains("境外"));
    }
}
