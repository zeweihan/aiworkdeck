package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「平台服务」面板的读写端点。
 *
 * <p>用户视角：8 项外部服务各自是「平台代采 / 自备 Key / 本地」哪一档、能不能切、为什么不能切。
 *
 * <p>鉴权同 {@code EntitlementController}：档位是<b>机器级</b>状态
 * （{@code system_setting} 里没有 userId 维度），server 模式下仅 admin 可改——
 * 否则团队服务器上任何一个租户都能把全服的搜索切走。local-mode 恒放行。
 */
@RestController
@Slf4j
public class PlatformServiceController {

    private final ExternalProviderResolver resolver;
    private final SystemSettingService systemSettingService;
    private final AccountService accountService;
    private final MachineAccountGuard machineAccountGuard;
    private final PlatformGatewayClient platformGatewayClient;

    public PlatformServiceController(ExternalProviderResolver resolver,
                                     SystemSettingService systemSettingService,
                                     AccountService accountService,
                                     MachineAccountGuard machineAccountGuard,
                                     PlatformGatewayClient platformGatewayClient) {
        this.resolver = resolver;
        this.systemSettingService = systemSettingService;
        this.accountService = accountService;
        this.machineAccountGuard = machineAccountGuard;
        this.platformGatewayClient = platformGatewayClient;
    }

    @GetMapping("/api/platform-services")
    public Map<String, Object> list(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        machineAccountGuard.requireMachineScope(sessionId);

        PlatformPricing pricing = fetchPricingQuietly();

        List<Map<String, Object>> services = new ArrayList<>();
        for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
            Map<String, Object> item = new HashMap<>();
            item.put("service", d.service());
            item.put("provider", resolver.resolve(d.service()).settingValue());
            item.put("hasLocal", d.hasLocal());
            item.put("hasByokCredentials", hasByokCredentials(d));
            // 「这项服务平台侧开放了没有」只有官网知道（service_pricing.enabled）。
            // 拿不到时给 null 而不是 false：**「不知道」不等于「未开放」**——
            // 一次网络抖动就把六项全标成未开放，比不显示这个状态更糟。
            // 前端对 null 应显示「—」，与 ai-usage 那条「查不到用量显示破折号不显示 0」同口径。
            item.put("enabled", pricing == null ? null : pricing.enabled().get(d.service()));
            services.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("services", services);
        // 平台档只在 local-mode 开放（设计决策 D5）。前端据此决定「平台代采」这个选项
        // 是展示为不可选 + 说明，还是整个不出现。
        data.put("platformAvailable", resolver.platformAvailable());
        data.put("accountConnected", accountService.currentKeyOrNull() != null);
        // pricingAvailable=false 时前端不许把 enabled/余额当成真值，显示「—」。
        data.put("pricingAvailable", pricing != null);
        data.put("balanceCents", pricing == null ? null : pricing.balanceCents());
        // 未结算的预扣。设计 §4.6 要求这笔钱「必须可解释」：一场两小时录音的预扣
        // 会把余额压低、进而让 PlatformCreditsGate 拦住 AI 对话——用户会同时发现
        // 转写和对话都停了，而没有这个数字他无从知道是转写占住的。
        data.put("pendingHoldCents", pricing == null ? null : pricing.pendingHoldCents());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /**
     * 切档。
     *
     * <p><b>不做「切到 platform 时顺手校验余额」</b>：那会让一次设置动作依赖网络，
     * 且余额是会变的——闸门该留在真正调用的那一刻（网关自己回 no_credits），
     * 不该把用户卡在设置页。
     */
    @PostMapping("/api/platform-services/{service}/provider")
    public Map<String, Object> setProvider(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable String service,
            @RequestBody Map<String, String> body) {
        machineAccountGuard.requireMachineScope(sessionId);

        ExternalServiceProvider.Descriptor descriptor;
        try {
            descriptor = ExternalServiceProvider.descriptor(service);
        } catch (IllegalArgumentException e) {
            return error(LangText.of("未知的服务：" + service, "Unknown service: " + service));
        }

        ExternalServiceProvider target = ExternalServiceProvider.parse(body.get("provider"), null);
        if (target == null) {
            return error(LangText.of("档位取值不合法", "Invalid provider value"));
        }
        if (target == ExternalServiceProvider.LOCAL && !descriptor.hasLocal()) {
            return error(LangText.of("该服务没有本地档", "This service has no local mode"));
        }
        if (target == ExternalServiceProvider.PLATFORM && !resolver.platformAvailable()) {
            return error(LangText.of(
                    "团队服务器与云端实例不支持平台代采，请使用自备 Key",
                    "Team servers and cloud instances do not support platform-sourced services; use your own key"));
        }

        systemSettingService.set(ExternalProviderResolver.providerKey(service), target.settingValue());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", Map.of("service", service, "provider", target.settingValue()));
        return result;
    }

    /** 官网单价表的一次快照：哪几家开放了、余额多少、有多少钱被未结算的预扣占着。 */
    private record PlatformPricing(Map<String, Boolean> enabled, Integer balanceCents, Integer pendingHoldCents) {}

    /** 单价表的取数超时。这是设置页的一次装载，用户在等着，不值得为它挂很久。 */
    private static final int PRICING_TIMEOUT_SECONDS = 8;

    /**
     * 取一次官网单价表；<b>任何失败都只降级这一段，绝不让整个设置页打不开</b>。
     *
     * <p>同 licensing-billing 地雷 6 的口径：权益/额度类信息取不到时给「不知道」，
     * 而不是把用户锁在外面。未连账户、非 local-mode、网关不可达、官网正在发版，
     * 这几种都只是「这一段显示不出来」，不该连档位切换都用不了——
     * 而档位切换恰恰是用户在网关出问题时唯一的自救手段。
     */
    private PlatformPricing fetchPricingQuietly() {
        if (!resolver.platformAvailable() || accountService.currentKeyOrNull() == null) {
            return null;
        }
        try {
            JsonNode root = platformGatewayClient.getPricing(PRICING_TIMEOUT_SECONDS);
            Map<String, Boolean> enabled = new HashMap<>();
            for (JsonNode row : root.path("pricing")) {
                String service = row.path("service").asText("");
                if (service.isEmpty()) continue;
                // 同一服务可能有多行（通配 + 精确 op）。只要有一行开着就算这项服务可用——
                // 用户在设置页关心的是「这项功能能不能用」，不是某个具体 op 的开关。
                enabled.merge(service, row.path("enabled").asBoolean(false), (a, b) -> a || b);
            }
            return new PlatformPricing(
                    enabled,
                    root.hasNonNull("balanceCents") ? root.get("balanceCents").asInt() : null,
                    root.hasNonNull("pendingHoldCents") ? root.get("pendingHoldCents").asInt() : null);
        } catch (RuntimeException e) {
            log.debug("取单价表失败，平台服务页降级显示: {}", e.toString());
            return null;
        }
    }

    private boolean hasByokCredentials(ExternalServiceProvider.Descriptor d) {
        for (String key : d.byokCredentialKeys()) {
            String value = systemSettingService.get(key, null);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    /** 业务错误一律 code=1，**绝不带 code=4010**——那会被前端判成掉线并清会话。 */
    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
