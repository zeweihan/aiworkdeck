package com.checkba.service.platform;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 档位判定，重点是<b>平台网关只在 local-mode 开放</b>（设计决策 D5）。
 *
 * <p>这道闸在解析层而不是调用点：让「哪些形态能用平台档」只有一处判据。
 * 漏掉它的后果是全体租户的外部服务费用记到那台机器所连的公司账户上，
 * 一个租户写脚本刷就是刷我们自己的 Credits。
 */
class ExternalProviderResolverTest {

    private static SystemSettingService settingsWith(Map<String, String> rows) {
        SystemSettingService svc = mock(SystemSettingService.class);
        when(svc.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return rows.containsKey(key) ? rows.get(key) : inv.getArgument(1);
        });
        return svc;
    }

    @Test
    @DisplayName("local-mode：设置写什么就是什么")
    void localModeHonoursSetting() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.search.provider", "platform");
        rows.put("external.asr.provider", "local");
        rows.put("external.ocr.provider", "byok");
        ExternalProviderResolver r = new ExternalProviderResolver(settingsWith(rows), true);

        assertTrue(r.platformAvailable());
        assertEquals(ExternalServiceProvider.PLATFORM, r.resolve("search"));
        assertEquals(ExternalServiceProvider.LOCAL, r.resolve("asr"));
        assertEquals(ExternalServiceProvider.BYOK, r.resolve("ocr"));
    }

    @Test
    @DisplayName("非 local-mode：即使设置写着 platform 也降级为 byok")
    void serverModeNeverUsesPlatform() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.search.provider", "platform");
        rows.put("external.qichacha.provider", "platform");
        ExternalProviderResolver r = new ExternalProviderResolver(settingsWith(rows), false);

        assertFalse(r.platformAvailable());
        // awdk_ 明文永不落库，server 侧对已桥接用户根本没有可打网关的 per-user 凭据；
        // 用机器级 Key 顶上就是全体租户共花公司账户的 Credits
        assertEquals(ExternalServiceProvider.BYOK, r.resolve("search"));
        assertEquals(ExternalServiceProvider.BYOK, r.resolve("qichacha"));
    }

    @Test
    @DisplayName("非 local-mode：local 档不受影响（本地模型与账户无关）")
    void serverModeKeepsLocalMode() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.asr.provider", "local");
        ExternalProviderResolver r = new ExternalProviderResolver(settingsWith(rows), false);
        assertEquals(ExternalServiceProvider.LOCAL, r.resolve("asr"));
    }

    @Test
    @DisplayName("没写过档位时：local-mode 默认 platform，server 模式默认 byok")
    void defaultsDependOnMode() {
        assertEquals(ExternalServiceProvider.PLATFORM,
                new ExternalProviderResolver(settingsWith(new HashMap<>()), true).resolve("search"));
        assertEquals(ExternalServiceProvider.BYOK,
                new ExternalProviderResolver(settingsWith(new HashMap<>()), false).resolve("search"));
    }

    @Test
    @DisplayName("非法取值回落而不是抛异常——一个坏设置值不该让整个功能挂掉")
    void invalidValueFallsBack() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.search.provider", "garbage");
        assertEquals(ExternalServiceProvider.PLATFORM,
                new ExternalProviderResolver(settingsWith(rows), true).resolve("search"));
    }

    @Test
    @DisplayName("六家服务的描述表完整，且 AI 与语音合成不在其中")
    void descriptorTableIsComplete() {
        assertEquals(6, ExternalServiceProvider.ALL.size());
        for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
            assertFalse(d.byokCredentialKeys().isEmpty(),
                    d.service() + " 缺少 BYOK 凭证键，回填会把它误判成空配置");
        }
        // AI 走的是「凭证下发 + 桌面直连」那条通路，不能改成网关代理（设计 §3 通路 A）
        assertTrue(ExternalServiceProvider.ALL.stream().noneMatch(d -> d.service().equals("ai")));
        assertTrue(ExternalServiceProvider.descriptor("asr").hasLocal());
        // 语音合成已整体移到本机一条路，不再进档位框架
        assertTrue(ExternalServiceProvider.ALL.stream().noneMatch(d -> d.service().equals("tts")));
        assertFalse(ExternalServiceProvider.descriptor("search").hasLocal());
    }
}
