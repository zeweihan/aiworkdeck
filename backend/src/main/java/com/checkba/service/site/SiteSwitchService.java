package com.checkba.service.site;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountService;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切站编排（双主站设计 §2.4）。
 *
 * <p>切站 = 换了一个**完全不同的商业实体**。本地一切从旧站拿来的东西都必须当场作废，
 * 否则用户会带着一堆在新站上必然 401 的凭据进产品，而每一处报错都指向错误的方向。
 *
 * <table>
 *   <caption>清理表</caption>
 *   <tr><th>状态</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>account.json</td><td>删</td><td>Key 属于旧站</td></tr>
 *   <tr><td>entitlements.json</td><td>删</td><td>权益是旧站发的</td></tr>
 *   <tr><td>platform-ai-key.json</td><td>删</td><td>runtime key 由旧站 provision，额度记在旧站账上</td></tr>
 *   <tr><td>license.json（mode=account）</td><td>删</td><td>授权票据是旧站 verify-key 发的</td></tr>
 *   <tr><td>license.json（mode=trial）</td><td><b>留</b></td><td>试用码离线验签、站点无关；抹掉等于把人踢回未解锁</td></tr>
 *   <tr><td>storage-location</td><td>留</td><td>与站点无关</td></tr>
 *   <tr><td>项目数据库</td><td>留</td><td>与站点无关</td></tr>
 * </table>
 *
 * <p>本类只**调用** {@code service/ai/} 下的三个既有出口（清平台密钥缓存、重置对账基线、
 * 供应商降级），不修改它们 —— 与 {@code AccountController.disconnect} 的做法逐字一致。
 */
@Service
@Slf4j
public class SiteSwitchService {

    private final SiteProfileService siteProfileService;
    private final LicenseService licenseService;
    private final AccountService accountService;
    private final EntitlementService entitlementService;
    private final PlatformAiChannel platformAiChannel;
    private final PlatformUsageAccountant platformUsageAccountant;
    private final ChatModelFactory chatModelFactory;

    public SiteSwitchService(SiteProfileService siteProfileService,
                             LicenseService licenseService,
                             AccountService accountService,
                             EntitlementService entitlementService,
                             PlatformAiChannel platformAiChannel,
                             PlatformUsageAccountant platformUsageAccountant,
                             ChatModelFactory chatModelFactory) {
        this.siteProfileService = siteProfileService;
        this.licenseService = licenseService;
        this.accountService = accountService;
        this.entitlementService = entitlementService;
        this.platformAiChannel = platformAiChannel;
        this.platformUsageAccountant = platformUsageAccountant;
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * 切到目标站点。目标 == 当前时**幂等**：什么都不清，直接返回。
     *
     * @throws IllegalArgumentException 站点被钉住 / id 未知或未启用
     */
    public synchronized Map<String, Object> switchTo(String siteId) {
        String from = siteProfileService.currentSite();
        Map<String, Object> result = new LinkedHashMap<>();
        if (from.equals(siteId)) {
            result.put("site", from);
            result.put("changed", false);
            result.put("restartRecommended", false);
            return result;
        }

        // 先落盘再清理：写盘失败就整个动作放弃，不该留下「凭据清了但站点没换」的中间态
        siteProfileService.persistSelection(siteId);

        boolean licenseCleared = licenseService.deactivateAccountMode();
        boolean accountCleared = accountService.isConnected();
        if (accountCleared) {
            accountService.disconnect();
        }
        entitlementService.clearAccountCache();
        platformAiChannel.clearCache();
        platformUsageAccountant.resetBaseline();
        // 平台通道此刻必然取不到 key，不降级会出现「界面显示通道正常选中、每条消息都报未连接账户」
        String fallback = chatModelFactory.demotePlatformProvider();

        log.info("站点切换 {} -> {}（授权清除={}，账户断开={}）", from, siteId, licenseCleared, accountCleared);

        result.put("site", siteId);
        result.put("changed", true);
        result.put("licenseCleared", licenseCleared);
        result.put("accountCleared", accountCleared);
        if (fallback != null) {
            result.put("aiProviderFallback", fallback);
        }
        // 广场与统计上报的地址在属性层固化，下次启动才指向新站（见 SiteProfileService 类注释）
        result.put("restartRecommended", true);
        return result;
    }
}
