package com.checkba.service.site;

import com.checkba.service.account.AccountEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「当前站点」的唯一解析出口（双主站设计 §2.3）。
 *
 * <p>启动时的站点已由 {@link SiteEnvironmentPostProcessor} 解析并注入属性层，
 * 因此 registry / telemetry / 账户基址这三类地址的既有消费方一行都没改。
 * 本类负责两件属性层做不到的事：
 * <ol>
 *   <li>把站点**展示口径**（id、展示名、账户页地址）交给前端与错误文案；</li>
 *   <li>切站时改写 {@code site.json} 并让账户链路**当场**改指向
 *       （见 {@link #baseUrl()}，{@link SiteSwitchService} 是唯一调用方）。</li>
 * </ol>
 *
 * <h3>切站的生效范围（有意分成两段，别当 bug 修）</h3>
 * <ul>
 *   <li><b>当场生效</b>：账户连接、解锁门在线校验、平台 AI 通道取 key —— 都走 {@link #baseUrl()}；</li>
 *   <li><b>下次启动生效</b>：插件/Skill 广场与统计上报 —— 它们在属性层固化。
 *       切站是低频且破坏性的动作（会清掉账户与权益），用户本就要重新走一遍连接流程；
 *       为了这两条不常用的通道把三个 {@code service/ai/} 下的类改成运行期可变，不划算。</li>
 * </ul>
 */
@Service
@Slf4j
public class SiteProfileService {

    private static final String BUILTIN_SITE_ID = "cn";

    private final boolean localMode;
    private final SiteProperties properties;
    private final Path stateDir;

    /** {@code @Value} 拿到的**有效**账户基址：属性层已把站点解析结果算进去了。 */
    private final String configuredBaseUrl;

    /** 站点被显式钉住（环境变量/命令行覆盖，或团队服务器模式）时为 true，此时不许切站。 */
    private final boolean pinned;

    private volatile String currentSiteId;

    public SiteProfileService(
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${ai.account.base-url:https://www.aiworkdeck.com}") String configuredBaseUrl,
            @Value("${ai.account.resolved-site:}") String resolvedSite,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir,
            SiteProperties properties) {
        this.localMode = localMode;
        this.properties = properties;
        this.stateDir = Path.of(stateDir);
        // 与 LicenseService / AccountService 共用同一条红线（https，回环 http 例外）
        this.configuredBaseUrl = AccountEndpoint.requireSecure(configuredBaseUrl);
        this.currentSiteId = resolveStartupSite(resolvedSite);
        this.pinned = !localMode
                || !this.configuredBaseUrl.equals(profileOf(this.currentSiteId).baseUrl());
        // 启动强不变式：站点表里配的每个 base-url 都要过协议校验，
        // 否则用户切过去才发现配错，此时本地授权已经被清掉了
        properties.getSites().forEach((id, site) -> {
            if (site.isEnabled() && site.getBaseUrl() != null && !site.getBaseUrl().isBlank()) {
                AccountEndpoint.requireSecure(site.getBaseUrl());
            }
        });
        log.info("当前站点: {}（{}）", this.currentSiteId, pinned ? "已由配置钉定" : "可切换");
    }

    /**
     * 钉死在给定基址的实例，等价于「配置里写死了 {@code ai.account.base-url}」的团队服务器形态：
     * {@link #baseUrl()} 恒回该值、{@link #multiSite()} 恒 false、不能切站。
     *
     * <p>供单测与任何「站点无关」的内嵌场景直接构造 —— 它们关心的是基址，不是站点体系。
     * 协议校验照旧执行，所以「http 非回环地址必须拒绝」这条红线在这条路上一样成立。
     */
    public static SiteProfileService pinnedTo(String baseUrl) {
        return new SiteProfileService(false, baseUrl, "",
                System.getProperty("java.io.tmpdir"), new SiteProperties());
    }

    // ==================== 读 ====================

    public String currentSite() {
        return currentSiteId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public SiteProfile profile() {
        return profileOf(currentSiteId);
    }

    /**
     * 账户链路的基址。被钉住时恒用配置值（本地联调的 http://localhost:3000 靠这条生效）；
     * 否则用当前站点的值，切站后当场改指向。
     */
    public String baseUrl() {
        return pinned ? configuredBaseUrl : profile().baseUrl();
    }

    /** 当前站点展示名，用于错误文案与设置页。 */
    public String displayName() {
        return profile().displayName();
    }

    /** 可选站点（enabled 且配了 base-url）。被钉住或非 local-mode 时只回当前一个。 */
    public List<SiteProfile> availableSites() {
        if (pinned) return List.of(profile());
        List<SiteProfile> result = new ArrayList<>();
        for (Map.Entry<String, SiteProperties.Site> entry : properties.getSites().entrySet()) {
            SiteProperties.Site site = entry.getValue();
            if (site.isEnabled() && site.getBaseUrl() != null && !site.getBaseUrl().isBlank()) {
                result.add(profileOf(entry.getKey()));
            }
        }
        return result.isEmpty() ? List.of(profile()) : result;
    }

    /**
     * 是否处于「多站点」形态。为 false 时前端不渲染站点选择器，
     * 错误文案也不追加「可能是站点选错了」那一句 —— 单站产品说这句话只会让人困惑。
     */
    public boolean multiSite() {
        return availableSites().size() > 1;
    }

    /** 除当前站点外的其余可选站点。站点错配的引导文案用它点名「你要找的可能是这个」。 */
    public List<SiteProfile> otherSites() {
        return availableSites().stream().filter(s -> !s.id().equals(currentSiteId)).toList();
    }

    public SiteProfile profileOf(String siteId) {
        SiteProperties.Site site = siteId == null ? null : properties.getSites().get(siteId);
        if (site == null || site.getBaseUrl() == null || site.getBaseUrl().isBlank()) {
            // 站点表没配（老配置文件、单测直接 new 出来的场景）：用有效基址兜出一个站点，
            // 保证 profile() 永不返回 null，调用方不需要到处判空
            return new SiteProfile(BUILTIN_SITE_ID, "AI WorkDeck", configuredBaseUrl,
                    configuredBaseUrl + "/api/registry", configuredBaseUrl + "/api/telemetry",
                    configuredBaseUrl);
        }
        String base = trimTrailingSlash(site.getBaseUrl());
        return new SiteProfile(
                siteId,
                orDefault(site.getDisplayName(), "AI WorkDeck"),
                base,
                orDefault(trimTrailingSlash(site.getRegistryBaseUrl()), base + "/api/registry"),
                orDefault(site.getTelemetryIngestUrl(), base + "/api/telemetry"),
                orDefault(site.getAccountPageUrl(), base));
    }

    // ==================== 写 ====================

    /**
     * 落盘站点选择并改写当前指向。
     *
     * <p><b>唯一合法调用方是 {@link SiteSwitchService}</b>：切站必须连带清掉旧站发来的
     * 授权票据、账户凭据、权益缓存与平台 AI 密钥，单独调本方法等于只换了指向、
     * 留下一堆在新站上必然 401 的旧凭据。
     *
     * @throws IllegalArgumentException 站点被钉住、id 未知或未启用
     */
    synchronized void persistSelection(String siteId) {
        if (pinned) {
            throw new IllegalArgumentException("当前部署已由配置指定站点，不能在应用内切换");
        }
        SiteProperties.Site site = siteId == null ? null : properties.getSites().get(siteId);
        if (site == null || !site.isEnabled() || site.getBaseUrl() == null || site.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("站点不存在或未启用：" + siteId);
        }
        try {
            SiteStateFile.write(stateDir,
                    new SiteStateFile.State(siteId, Instant.now().toString(), "user"));
        } catch (Exception e) {
            throw new IllegalStateException("站点选择写入失败，请检查磁盘权限", e);
        }
        currentSiteId = siteId;
        log.info("站点已切换到: {}", siteId);
    }

    // ==================== 内部 ====================

    private String resolveStartupSite(String resolvedSite) {
        if (resolvedSite != null && !resolvedSite.isBlank()) {
            return resolvedSite;
        }
        // 属性层没注入（非 local-mode、或站点表为空）：按配置的默认站点报告
        String fallback = properties.getDefaultSite();
        return fallback == null || fallback.isBlank() ? BUILTIN_SITE_ID : fallback;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
