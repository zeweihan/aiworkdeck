package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
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
public class PlatformServiceController {

    private final ExternalProviderResolver resolver;
    private final SystemSettingService systemSettingService;
    private final AccountService accountService;
    private final MachineAccountGuard machineAccountGuard;

    public PlatformServiceController(ExternalProviderResolver resolver,
                                     SystemSettingService systemSettingService,
                                     AccountService accountService,
                                     MachineAccountGuard machineAccountGuard) {
        this.resolver = resolver;
        this.systemSettingService = systemSettingService;
        this.accountService = accountService;
        this.machineAccountGuard = machineAccountGuard;
    }

    @GetMapping("/api/platform-services")
    public Map<String, Object> list(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        machineAccountGuard.requireMachineScope(sessionId);

        List<Map<String, Object>> services = new ArrayList<>();
        for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
            Map<String, Object> item = new HashMap<>();
            item.put("service", d.service());
            item.put("provider", resolver.resolve(d.service()).settingValue());
            item.put("hasLocal", d.hasLocal());
            item.put("hasByokCredentials", hasByokCredentials(d));
            services.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("services", services);
        // 平台档只在 local-mode 开放（设计决策 D5）。前端据此决定「平台代采」这个选项
        // 是展示为不可选 + 说明，还是整个不出现。
        data.put("platformAvailable", resolver.platformAvailable());
        data.put("accountConnected", accountService.currentKeyOrNull() != null);

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
