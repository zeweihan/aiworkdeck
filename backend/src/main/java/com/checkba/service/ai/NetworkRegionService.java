package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 网络区域判定：这台机器发出的请求，能不能到达只在国际网络可用的模型供应商。
 *
 * <p><b>为什么必须判定</b>：{@link AllowedModels.Region#INTERNATIONAL} 的模型在国内网络会被
 * OpenRouter 返回 403 "This model is not available in your region"，而 OpenRouter 的 API
 * **没有任何字段**能提前告知（{@code /models} 的 region 参数枚举只有 "eu"，语义是 EU 数据驻留；
 * 官方文档全文搜不到这句 403 文案）。也不能靠「换 provider 绕开」——实测
 * {@code anthropic/claude-sonnet-5} 的 8 个 endpoint 全在 US/EU/global，没有亚太友好 endpoint。
 * 所以判据只能是我们自己的信号。
 *
 * <p><b>为什么在后端用 JVM 信号而不是前端 navigator.language</b>：渲染进程里
 * {@code utils/zetaOfficeBoot.js} 主动把 {@code navigator.language} shim 成 zh-CN
 * （为修 en-GB 系统拿到英文编辑器界面加的），前端读到的语言不可信；JVM 完全不受该 shim 影响。
 *
 * <p><b>误判方向是刻意选的</b>：两个信号任一指向中国大陆就判 DOMESTIC（而不是要求两个都指向）。
 * 因为两种误判的代价不对称——误判成境内只是把国际模型藏起来（用户可在设置里手动覆盖），
 * 误判成境外则会把必然 403 的模型摆在选择器里让用户踩。宁可少给选项，不可给坏选项。
 *
 * <p><b>本地判定必然对一部分人是错的</b>（出差、挂代理、公司专线出境），所以
 * {@code ai.networkRegion} 手动覆盖不是隐藏兜底而是一等设置，设置页必须给入口。
 *
 * <p>港澳台**不算**大陆：网络管制与出境路径不同，能直连国际供应商。
 */
@Service
public class NetworkRegionService {

    private static final Logger log = LoggerFactory.getLogger(NetworkRegionService.class);

    /** system_setting 键：auto（默认，按本机信号判定）| domestic | international。 */
    public static final String SETTING_KEY = "ai.networkRegion";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_DOMESTIC = "domestic";
    public static final String MODE_INTERNATIONAL = "international";

    /**
     * 中国大陆时区标识。含 JDK 保留的历史别名（Asia/Chungking、Asia/Harbin、Asia/Kashgar 等
     * 在新版 tzdata 里已并入 Asia/Shanghai / Asia/Urumqi，但旧系统与旧 JDK 仍可能报出来）。
     * 刻意不含 Asia/Hong_Kong / Asia/Macau / Asia/Taipei。
     */
    private static final Set<String> MAINLAND_ZONE_IDS = Set.of(
            "Asia/Shanghai",
            "Asia/Chongqing",
            "Asia/Chungking",
            "Asia/Harbin",
            "Asia/Urumqi",
            "Asia/Kashgar",
            "PRC");

    private final SystemSettingService systemSettingService;

    public NetworkRegionService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    /**
     * 设置页回显用的原始模式（auto / domestic / international）。
     * 非法值一律当 auto——设置项被写坏不该让模型选择器整个失效。
     */
    public String mode() {
        String raw = systemSettingService.get(SETTING_KEY, MODE_AUTO);
        if (raw == null || raw.isBlank()) return MODE_AUTO;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (MODE_DOMESTIC.equals(v) || MODE_INTERNATIONAL.equals(v) || MODE_AUTO.equals(v)) {
            return v;
        }
        log.warn("未知的 {} 取值 '{}'，按 auto 处理", SETTING_KEY, raw);
        return MODE_AUTO;
    }

    /** 当前生效的区域：手动覆盖优先，auto 时走 {@link #detect()}。 */
    public AllowedModels.Region effectiveRegion() {
        return switch (mode()) {
            case MODE_DOMESTIC -> AllowedModels.Region.GLOBAL;
            case MODE_INTERNATIONAL -> AllowedModels.Region.INTERNATIONAL;
            default -> detect();
        };
    }

    /** 手动覆盖是否生效（设置页据此提示「已手动指定，不随本机环境变化」）。 */
    public boolean isManuallyOverridden() {
        return !MODE_AUTO.equals(mode());
    }

    /**
     * 按本机 JVM 信号判定。国家或时区任一指向中国大陆即判大陆（返回 GLOBAL，即只放行区域无关模型）。
     *
     * <p>返回值用 {@link AllowedModels.Region} 而不是另造一个枚举：它表达的正是
     * 「这台机器能用到哪一档模型」，与 {@link AllowedModels#availableIn} 直接对接。
     */
    public AllowedModels.Region detect() {
        String country = Locale.getDefault().getCountry();
        String zoneId = TimeZone.getDefault().getID();
        boolean mainland = "CN".equalsIgnoreCase(country) || MAINLAND_ZONE_IDS.contains(zoneId);
        return mainland ? AllowedModels.Region.GLOBAL : AllowedModels.Region.INTERNATIONAL;
    }

    /** 供设置页展示判定依据，让用户能看懂为什么国际模型不见了。 */
    public String detectionBasis() {
        return "系统国家/地区=" + orDash(Locale.getDefault().getCountry())
                + "，时区=" + orDash(TimeZone.getDefault().getID());
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}
