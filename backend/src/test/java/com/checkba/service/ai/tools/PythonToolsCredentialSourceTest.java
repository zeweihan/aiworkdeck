package com.checkba.service.ai.tools;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 注入 Python 子进程的外部服务凭证必须<b>库优先、yml 兜底</b>，与同一批凭证的 Java 侧调用方
 * （{@code TushareService} / {@code QichachaService}）同源。
 *
 * <p>护栏的由来：这里早先只读 {@code @Value}，而用户在「系统管理 → 外部服务」填的企查查 /
 * Tushare 凭证写进的是 {@code system_setting}。两边不同源的后果很隐蔽——脚本拿到空值不会报
 * 「未配置」，只是查不到数据，AI 据此回答「没有查到该公司的信息」，看起来像数据源没有。
 *
 * <p>只钉 BYOK 档。platform 档下这三个变量刻意不注入（改走 Java 侧一等工具经平台网关，
 * 见设计文档 §5.5，随 P4 落地），那条口径由 P4 自己的测试守，这里不重复、也不预先冻结。
 */
class PythonToolsCredentialSourceTest {

    /** 工具方法本身不碰 legalTools / webTools，与 {@code RealToolBeans} 一样传 null 即可。 */
    private static PythonTools toolsWith(Map<String, String> rows,
                                         String ymlTushareToken,
                                         String ymlQichachaKey,
                                         String ymlQichachaSecret) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return rows.containsKey(key) ? rows.get(key) : inv.getArgument(1);
        });
        // 档位解析恒回 byok：本类验的是**取值来源**（库优先），与档位过滤是两件事。
        com.checkba.service.platform.ExternalProviderResolver resolver =
                new com.checkba.service.platform.ExternalProviderResolver(settings, true);
        PythonTools tools = new PythonTools(null, null, settings, resolver);
        ReflectionTestUtils.setField(tools, "tushareToken", ymlTushareToken);
        ReflectionTestUtils.setField(tools, "qichachaKey", ymlQichachaKey);
        ReflectionTestUtils.setField(tools, "qichachaSecret", ymlQichachaSecret);
        return tools;
    }

    @Test
    @DisplayName("BYOK 档：设置页填过的凭证必须盖过 yml 默认值")
    void databaseValuesWinOverYamlDefaults() {
        Map<String, String> rows = new HashMap<>();
        rows.put("external.qichacha.provider", "byok");
        rows.put("external.tushare.provider", "byok");
        rows.put("external.tushare.token", "db-tushare-token");
        rows.put("external.qichacha.key", "db-qichacha-key");
        rows.put("external.qichacha.secret", "db-qichacha-secret");

        Map<String, String> env = toolsWith(rows, "yml-tushare-token", "yml-qichacha-key", "yml-qichacha-secret")
                .resolveExternalCredentials();

        assertEquals("db-tushare-token", env.get("TUSHARE_TOKEN"));
        assertEquals("db-qichacha-key", env.get("QICHACHA_KEY"));
        assertEquals("db-qichacha-secret", env.get("QICHACHA_SECRET"));
    }

    @Test
    @DisplayName("库里没写过时回落 yml——靠配置文件供凭证的存量部署不能被打断")
    void yamlDefaultsRemainTheFallback() {
        Map<String, String> env =
                toolsWith(new HashMap<>(), "yml-tushare-token", "yml-qichacha-key", "yml-qichacha-secret")
                        .resolveExternalCredentials();

        assertEquals("yml-tushare-token", env.get("TUSHARE_TOKEN"));
        assertEquals("yml-qichacha-key", env.get("QICHACHA_KEY"));
        assertEquals("yml-qichacha-secret", env.get("QICHACHA_SECRET"));
    }

    @Test
    @DisplayName("哪儿都没配时是空串，不能把 \"null\" 当 token 传进容器")
    void unconfiguredCredentialsBecomeEmptyStrings() {
        Map<String, String> env = toolsWith(new HashMap<>(), null, null, null).resolveExternalCredentials();

        assertEquals("", env.get("TUSHARE_TOKEN"));
        assertEquals("", env.get("QICHACHA_KEY"));
        assertEquals("", env.get("QICHACHA_SECRET"));
    }
}
