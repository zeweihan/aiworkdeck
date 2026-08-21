package com.checkba.service.platform;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 存量档位回填。
 *
 * <p>这条护栏防的是一类<b>静默</b>回归：升级后没有任何报错，
 * 只是用户已经填好的 Key 不再被用、或者本地免费引擎被切成了要花钱的云服务。
 */
class ExternalProviderBackfillTest {

    /** 用一个内存 map 冒充 system_setting 表。 */
    private static SystemSettingService settingsWith(Map<String, String> rows) {
        SystemSettingService svc = mock(SystemSettingService.class);
        when(svc.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return rows.containsKey(key) ? rows.get(key) : inv.getArgument(1);
        });
        doAnswer(inv -> {
            rows.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(svc).set(anyString(), anyString());
        return svc;
    }

    private static ExternalProviderBackfill backfill(SystemSettingService settings,
                                                    Map<String, String> injected) {
        ExternalProviderResolver resolver = new ExternalProviderResolver(settings, true);
        return new ExternalProviderBackfill(
                settings, resolver,
                injected.getOrDefault("bocha", ""),
                injected.getOrDefault("ocrAk", ""),
                injected.getOrDefault("ocrSk", ""),
                injected.getOrDefault("asrAk", ""),
                injected.getOrDefault("asrAppKey", ""),
                injected.getOrDefault("ossBucket", ""),
                injected.getOrDefault("qichachaKey", ""),
                injected.getOrDefault("qichachaSecret", ""),
                injected.getOrDefault("tushare", ""),
                injected.getOrDefault("pkulaw", ""));
    }

    @Test
    @DisplayName("全新安装：一个凭证都没有，六项全部落 platform")
    void freshInstallGoesAllPlatform() {
        Map<String, String> rows = new HashMap<>();
        backfill(settingsWith(rows), Map.of()).backfill();

        for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
            assertEquals("platform", rows.get(ExternalProviderResolver.providerKey(d.service())),
                    d.service() + " 在全新安装上应落 platform");
        }
    }

    @Test
    @DisplayName("存量库里填过 Key 的服务显式落 byok，没填过的才落 platform")
    void existingCredentialsStayByok() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.qichacha.key", "qcc-key");
        rows.put("external.aliyunOcr.accessKeyId", "LTAI-xxx");
        SystemSettingService settings = settingsWith(rows);

        backfill(settings, Map.of()).backfill();

        // 这两家用户已经付过订阅费，切到 platform 就是让他为同一项服务付两遍钱
        assertEquals("byok", rows.get("external.qichacha.provider"));
        assertEquals("byok", rows.get("external.ocr.provider"));
        // 没填过的照常落 platform，零配置目标不受影响
        assertEquals("platform", rows.get("external.search.provider"));
        assertEquals("platform", rows.get("external.pkulaw.provider"));
    }

    @Test
    @DisplayName("环境变量注入的凭证也算已配——只查库会把配好的团队服务器判成空配置")
    void injectedEnvCredentialsCountAsByok() {
        Map<String, String> rows = new HashMap<>();
        // 团队服务器常用 PKULAW_TOKEN / QICHACHA_KEY 环境变量注入，这些值不在 system_setting 里
        backfill(settingsWith(rows), Map.of("pkulaw", "pku-token", "bocha", "bocha-key")).backfill();

        assertEquals("byok", rows.get("external.pkulaw.provider"));
        assertEquals("byok", rows.get("external.search.provider"));
        assertEquals("platform", rows.get("external.tushare.provider"));
    }

    @Test
    @DisplayName("已经显式写过档位的服务一个字都不动（回填只跑一次）")
    void doesNotOverwriteExplicitSettings() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.search.provider", "byok");
        rows.put("external.asr.provider", "local");
        SystemSettingService settings = settingsWith(rows);

        backfill(settings, Map.of()).backfill();

        assertEquals("byok", rows.get("external.search.provider"));
        // 用户特意切到本地档的服务不能被回填改回去
        assertEquals("local", rows.get("external.asr.provider"));
    }

    @Test
    @DisplayName("回填幂等：跑第二次写入 0 行")
    void backfillIsIdempotent() {
        Map<String, String> rows = new HashMap<>();
        SystemSettingService settings = settingsWith(rows);
        ExternalProviderBackfill b = backfill(settings, Map.of());

        assertEquals(ExternalServiceProvider.ALL.size(), b.backfill());
        assertEquals(0, b.backfill(), "第二次不该再写任何行");
    }

    /**
     * 内嵌 Tomcat 在 refresh() 收尾（finishRefresh -> startWebServer）就开始收连接，
     * 而 ApplicationReadyEvent 要等 refresh() 返回、CommandLineRunner 跑完才发出——
     * 只挂这一个事件，升级后第一批落在这个窗口里的请求会读到还没回填的默认档位。
     *
     * <p>这里不去起真的内嵌 Tomcat 掐时间点（那样测出来的是抖动，不是回归），
     * 而是验证回填这件事本身发生在 {@code ctx.refresh()} 之内、不依赖
     * ApplicationReadyEvent 被发出——这正是两种触发方式在时序上的真实差别。
     */
    @Test
    @DisplayName("回填必须在 refresh() 内完成，不能只挂 ApplicationReadyEvent（否则赶不上内嵌 Tomcat 开始收连接）")
    void backfillCompletesDuringContextRefreshWithoutApplicationReadyEvent() {
        Map<String, String> rows = new HashMap<>();
        SystemSettingService settings = settingsWith(rows);
        ExternalProviderResolver resolver = new ExternalProviderResolver(settings, true);

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(ExternalProviderBackfill.class, () -> new ExternalProviderBackfill(
                settings, resolver, "", "", "", "", "", "", "", "", "", ""));
        try {
            // 只 refresh()，刻意不发 ApplicationReadyEvent——对应内嵌 Tomcat 已经开始
            // 收连接、但 ApplicationReadyEvent 尚未发出的那个窗口。
            ctx.refresh();

            for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
                assertNotNull(rows.get(ExternalProviderResolver.providerKey(d.service())),
                        "回填必须在 refresh() 内完成，不能等 ApplicationReadyEvent 才跑：" + d.service());
            }
        } finally {
            ctx.close();
        }
    }
}
