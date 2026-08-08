package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网络区域判定测试。
 *
 * <p>判错的代价不对称：判成境外会把必然 403 region 的国际模型摆进选择器让用户踩，
 * 判成境内只是少给选项（设置页可手动覆盖）。所以这里重点守两件事：
 * ① 任一信号指向大陆就判大陆；② 港澳台不算大陆。
 *
 * <p><b>Locale / TimeZone 是 JVM 全局状态</b>：改了不还会污染同一 JVM 里其他测试
 * （日期格式化、字符串大小写、时间解析都吃它）。所有改动都必须走 {@link #withEnv} 的
 * try/finally 包装。
 */
@DisplayName("网络区域判定")
class NetworkRegionServiceTest {

    private SystemSettingService settings;
    private NetworkRegionService service;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        service = new NetworkRegionService(settings);
        // 默认按「没写过设置项」处理：get 返回默认值 auto
        when(settings.get(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1, String.class));
    }

    private void setMode(String raw) {
        when(settings.get(NetworkRegionService.SETTING_KEY, NetworkRegionService.MODE_AUTO)).thenReturn(raw);
    }

    // ==================== mode() ====================

    @Test
    @DisplayName("mode 合法值原样返回，大小写与空白归一")
    void modeNormalizes() {
        setMode("domestic");
        assertEquals(NetworkRegionService.MODE_DOMESTIC, service.mode());

        setMode("  INTERNATIONAL  ");
        assertEquals(NetworkRegionService.MODE_INTERNATIONAL, service.mode());

        setMode("Auto");
        assertEquals(NetworkRegionService.MODE_AUTO, service.mode());
    }

    @Test
    @DisplayName("非法值、null、空串一律回落 auto——设置项写坏不该让模型选择器整个失效")
    void modeFallsBackToAutoOnGarbage() {
        setMode("cn");
        assertEquals(NetworkRegionService.MODE_AUTO, service.mode());

        setMode("");
        assertEquals(NetworkRegionService.MODE_AUTO, service.mode());

        setMode("   ");
        assertEquals(NetworkRegionService.MODE_AUTO, service.mode());

        setMode(null);
        assertEquals(NetworkRegionService.MODE_AUTO, service.mode());
    }

    // ==================== effectiveRegion() / isManuallyOverridden() ====================

    @Test
    @DisplayName("手动 domestic：不看本机环境，只放行区域无关模型")
    void manualDomesticIgnoresEnvironment() {
        setMode("domestic");
        withEnv(Locale.US, "America/New_York", () -> {
            assertEquals(AllowedModels.Region.GLOBAL, service.effectiveRegion());
            assertTrue(service.isManuallyOverridden());
        });
    }

    @Test
    @DisplayName("手动 international：即使本机像在大陆也放行全部模型（挂代理/专线出境）")
    void manualInternationalIgnoresEnvironment() {
        setMode("international");
        withEnv(Locale.CHINA, "Asia/Shanghai", () -> {
            assertEquals(AllowedModels.Region.INTERNATIONAL, service.effectiveRegion());
            assertTrue(service.isManuallyOverridden());
        });
    }

    @Test
    @DisplayName("auto 时 effectiveRegion 等于 detect，且不算手动覆盖")
    void autoDelegatesToDetect() {
        setMode("auto");
        withEnv(Locale.CHINA, "Asia/Shanghai", () -> {
            assertFalse(service.isManuallyOverridden());
            assertEquals(service.detect(), service.effectiveRegion());
            assertEquals(AllowedModels.Region.GLOBAL, service.effectiveRegion());
        });
    }

    // ==================== detect() ====================

    @Test
    @DisplayName("国家=CN 即判大陆，哪怕时区在境外（出差带机器）")
    void countryCnAloneMeansMainland() {
        withEnv(Locale.CHINA, "America/New_York",
                () -> assertEquals(AllowedModels.Region.GLOBAL, service.detect()));
    }

    @Test
    @DisplayName("时区=Asia/Shanghai 即判大陆，哪怕系统语言是英文")
    void mainlandZoneAloneMeansMainland() {
        withEnv(Locale.US, "Asia/Shanghai",
                () -> assertEquals(AllowedModels.Region.GLOBAL, service.detect()));
    }

    @Test
    @DisplayName("Asia/Hong_Kong 不算大陆：网络管制与出境路径不同，能直连国际供应商")
    void hongKongIsNotMainland() {
        withEnv(Locale.US, "Asia/Hong_Kong",
                () -> assertEquals(AllowedModels.Region.INTERNATIONAL, service.detect()));
        // 连 locale 也是港台的情况：country=HK/TW 不是 CN，仍判国际
        withEnv(Locale.forLanguageTag("zh-HK"), "Asia/Hong_Kong",
                () -> assertEquals(AllowedModels.Region.INTERNATIONAL, service.detect()));
        withEnv(Locale.forLanguageTag("zh-TW"), "Asia/Taipei",
                () -> assertEquals(AllowedModels.Region.INTERNATIONAL, service.detect()));
    }

    @Test
    @DisplayName("en-US + America/New_York 判国际")
    void enUsNewYorkIsInternational() {
        withEnv(Locale.US, "America/New_York",
                () -> assertEquals(AllowedModels.Region.INTERNATIONAL, service.detect()));
    }

    @Test
    @DisplayName("detectionBasis 同时给出国家与时区，设置页要靠它解释国际模型为什么不见了")
    void detectionBasisMentionsBothSignals() {
        withEnv(Locale.CHINA, "Asia/Shanghai", () -> {
            String basis = service.detectionBasis();
            assertTrue(basis.contains("CN"), "判定依据应含国家/地区，实际: " + basis);
            assertTrue(basis.contains("Asia/Shanghai"), "判定依据应含时区，实际: " + basis);
        });
    }

    /**
     * 在指定的 Locale / TimeZone 下跑一段断言，结束后**无论成败**都还原 JVM 全局状态。
     * 不还原会污染同一 JVM 里的其他测试。
     */
    private void withEnv(Locale locale, String zoneId, Runnable body) {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(locale);
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            body.run();
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }
}
