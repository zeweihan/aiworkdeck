package com.checkba.service.site;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点配置（前缀 {@code ai.account}）。
 *
 * <p>{@code base-url} 是既有键，保留原语义并继续被 LicenseService / AccountService /
 * AwdkLoginService 的 {@code @Value} 直接读取；本类绑定它只是为了让
 * {@link SiteProfileService} 判断「是否被显式钉住」。
 */
@Component
@ConfigurationProperties(prefix = "ai.account")
public class SiteProperties {

    /** 账户服务基址。非空且与解析出的站点不一致 = 被显式钉住（本地联调 / 团队服务器）。 */
    private String baseUrl = "";

    /** {@code site.json} 缺失时的站点。 */
    private String defaultSite = "cn";

    /** 站点表。key 是站点 id。 */
    private Map<String, Site> sites = new LinkedHashMap<>();

    public static class Site {
        /** 关闭的站点不出现在选择器里，也不能被选中。 */
        private boolean enabled = true;
        private String displayName = "";
        private String baseUrl = "";
        private String registryBaseUrl = "";
        private String telemetryIngestUrl = "";
        private String accountPageUrl = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getRegistryBaseUrl() { return registryBaseUrl; }
        public void setRegistryBaseUrl(String registryBaseUrl) { this.registryBaseUrl = registryBaseUrl; }
        public String getTelemetryIngestUrl() { return telemetryIngestUrl; }
        public void setTelemetryIngestUrl(String telemetryIngestUrl) { this.telemetryIngestUrl = telemetryIngestUrl; }
        public String getAccountPageUrl() { return accountPageUrl; }
        public void setAccountPageUrl(String accountPageUrl) { this.accountPageUrl = accountPageUrl; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getDefaultSite() { return defaultSite; }
    public void setDefaultSite(String defaultSite) { this.defaultSite = defaultSite; }
    public Map<String, Site> getSites() { return sites; }
    public void setSites(Map<String, Site> sites) { this.sites = sites; }
}
