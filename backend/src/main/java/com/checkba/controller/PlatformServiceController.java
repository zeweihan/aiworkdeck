package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.meeting.MeetingRecordingNotice;
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

    /**
     * 面板的<b>本地</b>那一半：档位、有没有本地档、有没有自备凭证、两个花费阈值。
     *
     * <p><b>这个端点一次出站请求都不发</b>，是本轮刻意改的（原先它在同步路径上打两次官网，
     * 最坏要等两轮超时加重试）。理由：<b>这一页正是网关出问题时用户唯一的自救入口</b>——
     * 官网整个挂掉的时候，他要能进来把档位切成自备 Key。把它的可用性挂在远程往返上，
     * 等于在最需要逃生门的时候把门焊死。
     *
     * <p>开放状态 / 余额 / 预扣 / 本月用量全部搬到 {@link #remote}，由前端在页面渲染完之后
     * 异步取、到了再填；没到就是「—」，一直没到就一直是「—」。
     */
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
        data.put("budget", budgetSettings());
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
     * 面板的<b>远端</b>那一半：哪几项开放了、余额、被预扣占住的钱、本月分服务消耗。
     *
     * <p>全部来自官网，因此<b>慢、可能取不到，而且不该挡住页面</b>。前端在页面渲染完之后
     * 单独取这一条，未到达时那几处显示「—」。
     *
     * <p>任何失败都回 {@code code=0} + 一个「什么都不知道」的载荷，<b>不回错误</b>：
     * 调用方要的是「填不上就算了」，把它做成 reject 只会让前端再写一遍同样的兜底。
     */
    @GetMapping("/api/platform-services/remote")
    public Map<String, Object> remote(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        machineAccountGuard.requireMachineScope(sessionId);

        PlatformPricing pricing = fetchPricingQuietly();

        Map<String, Object> data = new HashMap<>();
        // pricingAvailable=false 时前端不许把 enabled/余额当成真值，显示「—」。
        data.put("pricingAvailable", pricing != null);
        // 「这项服务平台侧开放了没有」只有官网知道（service_pricing.enabled）。
        // 取不到时整张表为空而不是一片 false：**「不知道」不等于「未开放」**——
        // 一次网络抖动就把六项全标成未开放，比不显示这个状态更糟。
        data.put("enabled", pricing == null ? Map.of() : pricing.enabled());
        data.put("balanceCents", pricing == null ? null : pricing.balanceCents());
        // 未结算的预扣。设计 §4.6 要求这笔钱「必须可解释」：一场两小时录音的预扣
        // 会把余额压低、进而让 PlatformCreditsGate 拦住 AI 对话——用户会同时发现
        // 转写和对话都停了，而没有这个数字他无从知道是转写占住的。
        data.put("pendingHoldCents", pricing == null ? null : pricing.pendingHoldCents());
        // 本月分服务消耗。取不到时整段为 null（而不是一堆 0）——前端据此显示「—」。
        // 「不知道」不等于「零」：显示 0 会让刚花掉 20 块的用户以为账没记上。
        //
        // **单价表没取到就不再问用量**：两条打的是同一个主机，前者失败意味着网关不可达，
        // 后者只会把同样的超时再等一遍（各自还会重试一次）。
        data.put("usage", pricing == null ? null : fetchUsageQuietly());

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

    // ==================== 花费闸门的两个设置项 ====================

    /**
     * 单次任务花费上限（分）。0 = 不限制。
     *
     * <p>设计 §4.9 的用户闸：<b>可恢复的确认，不是失败</b>——超过时桌面端问一句
     * 「本次任务已花费 N Credits，是否继续」，而不是把任务判死。
     * 刻意<b>不做</b>「每次调用前弹确认」：与「零配置、少打扰」的产品目标冲突，设计里明确否了。
     */
    private static final String KEY_TASK_LIMIT_CENTS = "platform.budget.taskLimitCents";
    /** 余额低于此值（分）时在「平台服务」面板提醒。0 = 不提醒。 */
    private static final String KEY_LOW_BALANCE_CENTS = "platform.budget.lowBalanceCents";

    /**
     * 写这两个阈值。
     *
     * <p><b>为什么落在这个控制器而不是 {@code AdminConfigController}</b>：
     * 这两个值与档位是同一类东西——机器级、无 userId 维度、由同一道
     * {@link MachineAccountGuard} 把关、在同一块面板上编辑。放进
     * {@code AdminConfigController.toSettingsUpdates} 会撞上它那个<b>有意跳过 null 字段</b>
     * 的行为（那次把 env 提供的 baseUrl 清成空串就是它），而「0 = 关闭」这种数值设置
     * 正是最容易被那条规则吃掉的形状：用户想关掉上限，字段却被当成「没填」而保持原值。
     */
    @PostMapping("/api/platform-services/budget")
    public Map<String, Object> setBudget(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, Object> body) {
        machineAccountGuard.requireMachineScope(sessionId);

        Integer taskLimit = parseCents(body.get("taskLimitCents"));
        Integer lowBalance = parseCents(body.get("lowBalanceCents"));
        if (taskLimit == null || lowBalance == null) {
            // 取值非法直接拒，不静默回落成 0——把「不限制」当成用户的意思是我们替他做决定
            return error(LangText.of("金额取值不合法，填 0 表示不启用",
                    "Invalid amount. Use 0 to turn the threshold off"));
        }

        systemSettingService.set(KEY_TASK_LIMIT_CENTS, String.valueOf(taskLimit));
        systemSettingService.set(KEY_LOW_BALANCE_CENTS, String.valueOf(lowBalance));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", budgetSettings());
        return result;
    }

    /** 非负整数（分）；null / 空 / 负数 / 非数字一律判非法。 */
    private Integer parseCents(Object raw) {
        if (raw == null) return null;
        try {
            long v = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            if (v < 0 || v > Integer.MAX_VALUE) return null;
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> budgetSettings() {
        Map<String, Object> budget = new HashMap<>();
        budget.put("taskLimitCents", readCents(KEY_TASK_LIMIT_CENTS));
        budget.put("lowBalanceCents", readCents(KEY_LOW_BALANCE_CENTS));
        return budget;
    }

    /** 库里存的是脏数据时按「没设」处理，不让一行坏设置把面板打不开。 */
    private int readCents(String key) {
        Integer v = parseCents(systemSettingService.get(key, "0"));
        return v == null ? 0 : v;
    }

    // ==================== 平台档转写的单独告知 ====================

    /**
     * 告知文本 + 这台机器确认过没有。
     *
     * <p>放在这个控制器而不是 {@code MeetingRecordingController}：确认是<b>机器级</b>状态
     * （同档位，{@code system_setting} 里没有 userId 维度），而会议那个控制器是项目成员级鉴权。
     * 用项目成员级的门守机器级的状态，等于团队服务器上任何一个成员都能替全机确认。
     */
    @GetMapping("/api/platform-services/asr-notice")
    public Map<String, Object> asrNotice(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        machineAccountGuard.requireMachineScope(sessionId);

        Map<String, Object> data = new HashMap<>();
        data.put("version", MeetingRecordingNotice.VERSION);
        data.put("body", MeetingRecordingNotice.body());
        data.put("acknowledged", MeetingRecordingNotice.acknowledged(systemSettingService));
        data.put("acknowledgedAt", MeetingRecordingNotice.acknowledgedAt(systemSettingService));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /**
     * 确认 / 撤回。
     *
     * <p><b>这里没有任何「确认了才放行」的服务端闸</b>，是刻意的：会议录音不出境，
     * 触发的是告知义务而非个保法第三十九条的单独同意，做成硬闸的代价是律师录完两小时会
     * 在点转写那一刻被拦住。呈现与把关都在录音开始之前的那块面板上，那是唯一
     * 「什么都还没发生」的时刻。理由详见 {@link MeetingRecordingNotice}。
     */
    @PostMapping("/api/platform-services/asr-notice")
    public Map<String, Object> acknowledgeAsrNotice(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, Object> body) {
        machineAccountGuard.requireMachineScope(sessionId);

        // 缺字段按「没确认」处理，绝不默认成 true——预先勾选的同意在个保法下无效，
        // 「客户端忘了传就算确认」是同一件事的服务端版本
        boolean acknowledged = Boolean.TRUE.equals(body.get("acknowledged"));
        systemSettingService.setMany(MeetingRecordingNotice.updates(acknowledged));

        return asrNotice(sessionId);
    }

    /** 官网单价表的一次快照：哪几家开放了、余额多少、有多少钱被未结算的预扣占着。 */
    private record PlatformPricing(Map<String, Boolean> enabled, Integer balanceCents, Integer pendingHoldCents) {}

    /** 单价表的取数超时。这是设置页的一次装载，用户在等着，不值得为它挂很久。 */
    private static final int PRICING_TIMEOUT_SECONDS = 8;
    /** 用量同理，且它排在单价表之后，两段加起来不能把设置页拖成十几秒才出现。 */
    private static final int USAGE_TIMEOUT_SECONDS = 8;

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

    /**
     * 取一次本月分服务消耗；<b>失败整段给 null，绝不退化成一堆 0</b>。
     *
     * <p>同 {@code ai-usage} 那条既有口径：查不到用量显示破折号，不显示 0。
     * 显示 0 是在陈述一个我们并不知道的事实——用户刚跑完一场两小时转写，
     * 面板告诉他本月花了 0，他的下一步是来问账是不是没记上。
     *
     * <p>与单价表分开取，也分开降级：一个挂了不该把另一个拖下水
     * （单价几乎不动、桌面端给它挂缓存；用量花一笔就要变）。
     */
    private Map<String, Object> fetchUsageQuietly() {
        if (!resolver.platformAvailable() || accountService.currentKeyOrNull() == null) {
            return null;
        }
        try {
            JsonNode root = platformGatewayClient.getMonthlyUsage(USAGE_TIMEOUT_SECONDS);
            Map<String, Integer> byService = new HashMap<>();
            for (JsonNode row : root.path("services")) {
                String service = row.path("service").asText("");
                if (!service.isEmpty()) byService.put(service, row.path("cents").asInt(0));
            }
            Map<String, Object> usage = new HashMap<>();
            usage.put("month", root.path("month").asText(""));
            usage.put("services", byService);
            usage.put("totalCents", root.path("totalCents").asInt(0));
            return usage;
        } catch (RuntimeException e) {
            // 官网还没上这条端点时这里就是 404 → MALFORMED，与网络抖动同样处理：
            // 面板照常打开，用量那一列显示「—」
            log.debug("取本月用量失败，用量列降级显示: {}", e.toString());
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
