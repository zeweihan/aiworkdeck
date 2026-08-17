package com.checkba.service.platform;

import com.checkba.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 「这项服务该走哪一档」的<b>唯一判定出口</b>。
 *
 * <h3>平台网关只在 local-mode 开放（设计决策 D5）</h3>
 * 非 local-mode（律所自建团队服务器、官方托管的 {@code addin.aiworkdeck.com} 云实例）
 * <b>恒为 BYOK</b>，与改造前逐字一致。
 *
 * <p>为什么不像 AI 那样做 per-user：{@code awdk_} 明文永不落库，server 侧对某个已桥接用户
 * 根本不存在可以拿去打网关的 Bearer 凭据（{@code PlatformAiUserScope} 给的是 userId，
 * 不是凭据）。AI 能做 per-user 是因为上游 OpenRouter 支持签发带 limit 的子密钥，
 * 而其余七家没有这个能力——这正是它们必须走网关代理而不是凭证下发的原因。
 *
 * <p>若强行让 server 侧用机器级 Key 打网关，结果是全体租户的搜索与企业数据费用记到
 * 那台机器所连的公司账户上，一个租户写脚本刷就是刷我们自己的 Credits。
 * 多租户实例今天的外部服务本来就是机器级共账，选 BYOK 是<b>零变化</b>。
 */
@Service
@Slf4j
public class ExternalProviderResolver {

    private final SystemSettingService systemSettingService;
    private final boolean localMode;

    public ExternalProviderResolver(SystemSettingService systemSettingService,
                                    @Value("${security.local-mode:false}") boolean localMode) {
        this.systemSettingService = systemSettingService;
        this.localMode = localMode;
    }

    public boolean platformAvailable() {
        return localMode;
    }

    /**
     * 解析某个服务当前生效的档位。
     *
     * <p>非 local-mode 下即使设置里写着 platform 也返回 BYOK——闸在这里而不是在调用点，
     * 是为了让「哪些形态能用平台档」只有一处判据。
     */
    public ExternalServiceProvider resolve(String service) {
        ExternalServiceProvider configured = ExternalServiceProvider.parse(
                systemSettingService.get(providerKey(service), null),
                ExternalServiceProvider.PLATFORM);
        if (!localMode && configured == ExternalServiceProvider.PLATFORM) {
            return ExternalServiceProvider.BYOK;
        }
        return configured;
    }

    public static String providerKey(String service) {
        return "external." + service + ".provider";
    }

    /** 该服务的档位是否已在库里显式写过。存量回填据此判断「这是不是一台没迁移过的老机器」。 */
    public boolean hasExplicitSetting(String service) {
        return systemSettingService.get(providerKey(service), null) != null;
    }
}
