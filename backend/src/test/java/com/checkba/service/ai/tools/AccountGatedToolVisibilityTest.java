// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.platform.ExternalServiceAvailability;
import com.checkba.service.platform.ExternalServiceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三个依赖账户连接的工具组件，各自报对了哪些工具不可用（dev-board#750）。
 *
 * <p>病灶实录：没连账户的桌面端问一句「有限责任公司股东优先购买权的行使期限」，模型跑了
 * 4 个 LLM 往返，其中 law_search 与 search_web 两轮原样拿回「尚未连接 AI WorkDeck 账户」，
 * 每轮 3~5 秒。模型看不见这些工具就不会去试。
 *
 * <p><b>本用例同时钉住「不许连坐」</b>：同组件里的本地工具（browse_url / read_document）
 * 绝不能跟着一起藏——藏了就是告诉模型「你连网页都打不开 / 连文件都读不了」。
 */
class AccountGatedToolVisibilityTest {

    private static ExternalServiceAvailability availability(boolean usable) {
        ExternalServiceAvailability a = mock(ExternalServiceAvailability.class);
        when(a.usable(anyString())).thenReturn(usable);
        return a;
    }

    /** 这三个组件的依赖是 @Autowired 字段注入，测试里直接塞。 */
    private static void inject(Object bean, String field, Object value) {
        try {
            Field f = bean.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(bean, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("字段名变了就要同步改这里：" + field, e);
        }
    }

    @Test
    @DisplayName("WebTools：未连接时只藏 search_web，browse_url 走本机 Playwright 照常下发")
    void webToolsHidesOnlySearch() {
        WebTools tools = new WebTools();
        inject(tools, "externalServiceAvailability", availability(false));
        assertEquals(Set.of("search_web"), tools.currentlyUnusableTools());

        inject(tools, "externalServiceAvailability", availability(true));
        assertTrue(tools.currentlyUnusableTools().isEmpty());
    }

    @Test
    @DisplayName("LegalTools：未连接时藏四个 law_*，read_document 绝不连坐")
    void legalToolsHidesOnlyPkulaw() {
        LegalTools tools = new LegalTools(null, null, null, null);
        inject(tools, "externalServiceAvailability", availability(false));

        Set<String> hidden = tools.currentlyUnusableTools();
        assertEquals(Set.of("law_search", "law_search_keyword", "law_recognition", "get_law_article"), hidden);
        assertFalse(hidden.contains("read_document"),
                "read_document 读的是项目里已有的文件、全程不出网，藏了等于告诉模型它连文件都读不了");

        inject(tools, "externalServiceAvailability", availability(true));
        assertTrue(tools.currentlyUnusableTools().isEmpty());
    }

    @Test
    @DisplayName("EnterpriseDataTools：企查查与 Tushare 分别判，连着一个不代表另一个也能用")
    void enterpriseToolsJudgeEachServiceSeparately() {
        EnterpriseDataTools tools = new EnterpriseDataTools(null, null);
        ExternalServiceAvailability mixed = mock(ExternalServiceAvailability.class);
        when(mixed.usable(ExternalServiceProvider.QICHACHA)).thenReturn(false);
        when(mixed.usable(ExternalServiceProvider.TUSHARE)).thenReturn(true);
        inject(tools, "externalServiceAvailability", mixed);

        assertEquals(Set.of("qichacha_query", "qichacha_ipr"), tools.currentlyUnusableTools());
    }

    @Test
    @DisplayName("配了演示桩目录时一个都不藏——那条路压根不出网")
    void demoFixturesKeepEverythingVisible() {
        EnterpriseDataTools tools = new EnterpriseDataTools(null, null);
        inject(tools, "externalServiceAvailability", availability(false));
        inject(tools, "enterpriseDemoFixturesDir", "/tmp/demo-fixtures");
        assertTrue(tools.currentlyUnusableTools().isEmpty());
    }

    @Test
    @DisplayName("没装配 availability（裸 new 的既有测试与评测 harness）时行为与改造前一致")
    void missingAvailabilityBeanHidesNothing() {
        assertTrue(new WebTools().currentlyUnusableTools().isEmpty());
        assertTrue(new LegalTools(null, null, null, null).currentlyUnusableTools().isEmpty());
        assertTrue(new EnterpriseDataTools(null, null).currentlyUnusableTools().isEmpty());
    }
}
