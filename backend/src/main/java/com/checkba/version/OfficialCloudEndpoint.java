package com.checkba.version;

import com.checkba.service.account.AccountEndpoint;

import java.net.URI;
import java.util.Locale;

/**
 * 官方团队案件库的地址解析。
 *
 * <p>「案件库地址」在 v2 里是用户手输的数据（{@code cloud_connection.server_url}），
 * 官方案件库上线后不该再让律师去背一个域名——本类是「不填地址时用哪个」的唯一出口。
 *
 * <p>三条分支，各有各的理由：
 * <ul>
 *   <li>{@code cloud.collab.base-url} 显式配置优先——自建与私有部署靠它把「官方」
 *       指到自己的服务器上（能力保留是拍板项，不做成写死的域名）；</li>
 *   <li>账户连的是国际站（{@code workdeck.ai} 系）时返回 {@code null}：官方案件库
 *       目前只有大陆一套，把国际站账户的案卷默默送进大陆服务器是数据跨境，宁可
 *       只留本地版本记录；</li>
 *   <li>其余（大陆站）走 {@link #CN_OFFICIAL_BASE_URL}。</li>
 * </ul>
 *
 * <p>这条通道上跑的是长期设备令牌，所以一律过 {@link AccountEndpoint#requireSecure}
 * （https，回环 http 例外供本地联调）——与账户 Key 那条通道同一条红线。
 */
public final class OfficialCloudEndpoint {

    /** 大陆站的官方团队案件库（与官网、插件云同一台 ECS 上的独立 case profile 实例）。 */
    public static final String CN_OFFICIAL_BASE_URL = "https://case.aiworkdeck.com";

    private OfficialCloudEndpoint() {
    }

    /**
     * @param configuredBaseUrl {@code cloud.collab.base-url}，留空表示按账户站点派生
     * @param accountBaseUrl    {@code ai.account.base-url}（local-mode 下已由 site.json 决定）
     * @return 官方案件库地址；{@code null} 表示本站暂不提供官方案件库
     * @throws IllegalArgumentException 显式配置了非 https 且非回环的地址
     */
    public static String resolve(String configuredBaseUrl, String accountBaseUrl) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return AccountEndpoint.requireSecure(configuredBaseUrl.trim());
        }
        if (isInternationalSite(accountBaseUrl)) {
            return null;
        }
        return CN_OFFICIAL_BASE_URL;
    }

    /**
     * 按**主机**判定站点，不用 {@code contains("workdeck.ai")}——那会把
     * {@code workdeck.ai.evil.com} 也算成国际站（同 AccountEndpoint 不用
     * {@code startsWith("127.")} 判回环的理由）。
     */
    private static boolean isInternationalSite(String accountBaseUrl) {
        if (accountBaseUrl == null || accountBaseUrl.isBlank()) return false;
        try {
            String host = URI.create(accountBaseUrl.trim()).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.equals("workdeck.ai") || normalized.endsWith(".workdeck.ai");
        } catch (Exception e) {
            return false;
        }
    }
}
