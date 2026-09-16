// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 请求级语言覆盖（dev-board#713）。
 *
 * <p>病灶：{@code app.language} 是 system_setting 里的全局单值，云后端（多租户）上它恒为
 * 默认的 zh-CN，而插件从来没声明过自己的界面语言——于是英文界面拿到中文回答。
 * 覆盖层让「这一次请求说英语」成为可能，同时保证不声明时行为与引入前一模一样。
 */
class AppLanguageScopeTest {

    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final AppLanguageService service = new AppLanguageService(settings);

    @AfterEach
    void tearDown() {
        LangText.reset();
        // 作用域是 ThreadLocal：用例之间必须干净，否则一条漏清的覆盖会把后面的用例全带跑
        assertNull(AppLanguageScope.current(), "用例结束时作用域必须已经恢复");
    }

    private void globalIs(String language) {
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN))).thenReturn(language);
    }

    @Test
    @DisplayName("归一化：认 zh-CN/en-US，也认 zh/en/en-GB/zh_CN；认不出的一律不覆盖")
    void normalizeAcceptsCommonLocaleShapes() {
        assertEquals("zh-CN", AppLanguageScope.normalize("zh-CN"));
        assertEquals("zh-CN", AppLanguageScope.normalize("zh"));
        assertEquals("zh-CN", AppLanguageScope.normalize("zh_CN"));
        assertEquals("zh-CN", AppLanguageScope.normalize("zh-Hant-TW"));
        assertEquals("en-US", AppLanguageScope.normalize("en-US"));
        assertEquals("en-US", AppLanguageScope.normalize(" EN "));
        assertEquals("en-US", AppLanguageScope.normalize("en-GB"));
        // 认不出来 = 不覆盖（回落全局设置），不是报错：语言不是准入条件
        assertNull(AppLanguageScope.normalize("ja-JP"));
        assertNull(AppLanguageScope.normalize(""));
        assertNull(AppLanguageScope.normalize("   "));
        assertNull(AppLanguageScope.normalize(null));
    }

    @Test
    @DisplayName("覆盖优先于全局设置：云后端 app.language 恒 zh-CN，英文请求照样拿到 en-US")
    void scopedLanguageWinsOverGlobalSetting() {
        globalIs(AppLanguageService.ZH_CN);
        assertEquals("zh-CN", service.language(), "没有声明时仍读全局设置");

        AppLanguageScope.run("en-US", () -> {
            assertEquals("en-US", service.language());
            assertTrue(service.isEnglish());
        });

        assertEquals("zh-CN", service.language(), "退出作用域后必须回到全局设置");
        assertFalse(service.isEnglish());
    }

    @Test
    @DisplayName("不声明语言 = 行为与覆盖层引入前完全一致（桌面端走的就是这条）")
    void unscopedRequestKeepsGlobalBehaviour() {
        globalIs(AppLanguageService.EN_US);
        assertTrue(service.isEnglish(), "全局设成英文时，不声明语言的调用方仍然拿英文");
        AppLanguageScope.run(null, () -> assertTrue(service.isEnglish()));
        AppLanguageScope.run("ja-JP", () -> assertTrue(service.isEnglish(), "认不出的值不许把英文改掉"));
    }

    @Test
    @DisplayName("嵌套安全：内层退出恢复外层值，而不是清空")
    void nestedScopesRestoreOuterValue() {
        globalIs(AppLanguageService.ZH_CN);
        AppLanguageScope.run("en-US", () -> {
            AppLanguageScope.run("zh-CN", () -> assertEquals("zh-CN", service.language()));
            assertEquals("en-US", service.language(), "内层退出后必须回到外层声明的语言");
            // 内层不声明语言时不许把外层的声明抹掉
            AppLanguageScope.run(null, () -> assertEquals("en-US", service.language()));
        });
    }

    @Test
    @DisplayName("作用域内抛异常也必须恢复（不恢复就会把语言泄给同一个线程上的下一个请求）")
    void scopeIsRestoredOnException() {
        globalIs(AppLanguageService.ZH_CN);
        assertThrows(IllegalStateException.class, () -> AppLanguageScope.run("en-US", () -> {
            throw new IllegalStateException("boom");
        }));
        assertNull(AppLanguageScope.current());
        assertEquals("zh-CN", service.language());
    }

    @Test
    @DisplayName("LangText 跟着覆盖走：插件懒建项目名等文案按请求语言产出")
    void langTextFollowsScopedLanguage() {
        globalIs(AppLanguageService.ZH_CN);
        LangText.register(service);
        assertEquals("插件临时项目", LangText.of("插件临时项目", "Plugin Temporary Project"));
        AppLanguageScope.run("en-US", () ->
                assertEquals("Plugin Temporary Project",
                        LangText.of("插件临时项目", "Plugin Temporary Project")));
    }

    @Test
    @DisplayName("覆盖只影响本线程：另一条线程仍读全局设置")
    void scopeDoesNotLeakAcrossThreads() throws Exception {
        globalIs(AppLanguageService.ZH_CN);
        String[] seen = new String[1];
        AppLanguageScope.run("en-US", () -> {
            Thread other = new Thread(() -> seen[0] = service.language());
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertEquals("zh-CN", seen[0],
                "ThreadLocal 不跨线程传递——正因为如此，异步编排那一轮必须由请求体里的 appLanguage 重建作用域");
    }
}
