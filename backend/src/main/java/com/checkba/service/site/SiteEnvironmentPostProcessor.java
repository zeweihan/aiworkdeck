package com.checkba.service.site;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把「当前站点」在**容器启动之前**灌进 Environment（双主站设计 §2.2）。
 *
 * <h3>为什么用 EnvironmentPostProcessor 而不是给每个消费方注入一个 Service</h3>
 * 站点相关的地址有四条，其中三条的消费方在 {@code service/ai/} 下
 * （{@code PluginMarketService}、{@code SkillProperties}、以及 telemetry 的上传服务）。
 * 在属性层解析，这些类一行都不用改——既避开了同期并行改造 AI 供应商体系的作用域，
 * 也让「新增一条站点相关地址」将来只需要在这里加一行映射。
 *
 * <h3>插在哪一层</h3>
 * 属性源插在 {@code systemEnvironment} **之后**，因此优先级为：
 * <pre>命令行 &gt; 系统属性 &gt; 环境变量 &gt; 本处注入 &gt; application.yml</pre>
 * 这个位置是刻意选的：
 * <ul>
 *   <li>要压过 {@code application.yml} 的默认值，否则站点选择根本不生效；</li>
 *   <li>又要输给环境变量，否则本地联调的 {@code AI_ACCOUNT_BASE_URL=http://localhost:3000}
 *       会被站点解析覆盖掉，官网契约就没有安全的验证场地了。</li>
 * </ul>
 *
 * <h3>三条不变式</h3>
 * <ol>
 *   <li><b>只在 local-mode 生效</b>。团队服务器与插件云后端面向哪个站是部署决策，
 *       由 yml/环境变量固定，不该被某个用户写下的 site.json 改掉。</li>
 *   <li><b>site.json 不存在时解析到 {@code ai.account.default-site}</b>（= cn），
 *       注入的值与 application.yml 里今天写死的值逐字相同 —— 存量安装行为零变化。</li>
 *   <li><b>任何异常都静默放弃注入</b>。站点是锦上添花，绝不能成为起不来的理由。</li>
 * </ol>
 */
public class SiteEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "awdSiteProfile";

    /** 站点 id 会被注入这个键，供 {@link SiteProfileService} 读取启动时的解析结果。 */
    static final String RESOLVED_SITE_KEY = "ai.account.resolved-site";

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor 是 HIGHEST_PRECEDENCE + 10；
        // 排在它后面才能读到 application.yml 与 profile 里的值
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            apply(environment);
        } catch (Exception e) {
            // 起不来比站点错更糟。这里不打日志：日志系统此刻尚未初始化
        }
    }

    private void apply(ConfigurableEnvironment environment) {
        if (!Boolean.parseBoolean(environment.getProperty("security.local-mode", "false"))) {
            return;
        }
        String siteId = resolveSiteId(environment);
        if (siteId == null) return;

        String prefix = "ai.account.sites." + siteId + ".";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RESOLVED_SITE_KEY, siteId);
        copy(environment, values, prefix + "base-url", "ai.account.base-url");
        copy(environment, values, prefix + "telemetry-ingest-url", "telemetry.ingest-url");

        String registryBase = environment.getProperty(prefix + "registry-base-url");
        if (registryBase != null && !registryBase.isBlank()) {
            String base = trimTrailingSlash(registryBase);
            values.put("ai.plugins.registry-url", base + "/plugins");
            values.put("ai.skills.registry-url", base + "/skills");
        }

        if (values.size() == 1) return; // 只有站点 id，说明站点表没配，别插一个空源

        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(PROPERTY_SOURCE_NAME);
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        String systemEnv = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (sources.contains(systemEnv)) {
            sources.addAfter(systemEnv, source);
        } else {
            sources.addLast(source);
        }
    }

    /** site.json 的选择优先，其次 default-site；解析出的站点必须存在且 enabled。 */
    private String resolveSiteId(ConfigurableEnvironment environment) {
        String chosen = SiteStateFile.read(stateDir(environment)).site();
        String fallback = orDefault(environment.getProperty("ai.account.default-site"), "cn");
        if (chosen != null && isEnabled(environment, chosen)) return chosen;
        return isEnabled(environment, fallback) ? fallback : null;
    }

    private static boolean isEnabled(ConfigurableEnvironment environment, String siteId) {
        String prefix = "ai.account.sites." + siteId + ".";
        String baseUrl = environment.getProperty(prefix + "base-url");
        if (baseUrl == null || baseUrl.isBlank()) return false;
        return Boolean.parseBoolean(environment.getProperty(prefix + "enabled", "true"));
    }

    /** 与 {@code @Value("${security.license.dir:${user.home}/.aiworkdeck}")} 的默认值保持一致。 */
    private static Path stateDir(ConfigurableEnvironment environment) {
        String dir = environment.getProperty("security.license.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home") + "/.aiworkdeck";
        }
        return Path.of(dir);
    }

    private static void copy(ConfigurableEnvironment environment, Map<String, Object> into,
                             String from, String to) {
        String value = environment.getProperty(from);
        if (value != null && !value.isBlank()) {
            into.put(to, value);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
