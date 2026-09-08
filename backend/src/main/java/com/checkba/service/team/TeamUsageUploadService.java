package com.checkba.service.team;

import com.checkba.service.LocalIdentityService;
import com.checkba.service.account.AccountService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队使用统计的上报（dev-board#496，设计 §5/§7）。节奏照 {@code TelemetryUploadService}：
 * 启动一次守护线程 + 每 24 小时一次，失败静默、下轮补传（窗口 30 天）。
 *
 * <p><b>四道闸，缺一不发</b>（顺序即判定顺序，最省事的先判）：
 * <ol>
 *   <li>本机开关 {@code team.usage.enabled} —— 默认关，机器主人不点就一个字节都不出去；</li>
 *   <li>local-mode —— server 模式（团队案件库 / 插件云实例）下账户是<b>机器级</b>状态，
 *       而使用数据是<b>每个租户各自的</b>，照发会把全服所有人的活动记在管理员账户名下；</li>
 *   <li>账户已连接 —— 没有 awdk_ 就没有身份，官网也认不出这是谁的数据；</li>
 *   <li>确实在某个团队里 —— 不在团队里上报没有任何接收方，白发一次请求还平添一条服务端记录。</li>
 * </ol>
 *
 * <p>今天永远不传：当天还没过完，聚合出来的是半天数据，传上去下次还得覆盖。
 */
@Slf4j
@Service
public class TeamUsageUploadService {

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** 补传窗口：与 {@link TeamUsageSettings#RETENTION_DAYS} 同为 30 天。 */
    static final int BACKFILL_DAYS = TeamUsageSettings.RETENTION_DAYS;

    private final TeamUsageSettings settings;
    private final TeamUsageRollupService rollupService;
    private final AccountService accountService;
    private final LocalIdentityService localIdentityService;
    private final TeamSettingsCache teamSettingsCache;

    public TeamUsageUploadService(TeamUsageSettings settings,
                                  TeamUsageRollupService rollupService,
                                  AccountService accountService,
                                  LocalIdentityService localIdentityService,
                                  TeamSettingsCache teamSettingsCache) {
        this.settings = settings;
        this.rollupService = rollupService;
        this.accountService = accountService;
        this.localIdentityService = localIdentityService;
        this.teamSettingsCache = teamSettingsCache;
    }

    @PostConstruct
    public void onStartup() {
        Thread t = new Thread(this::sync, "team-usage-upload-init");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = DAY_MS, initialDelay = DAY_MS)
    public void scheduledSync() {
        sync();
    }

    /** 后台轮次：全程静默失败（上报不是用户发起的动作，不该弹任何东西）。 */
    public void sync() {
        try {
            run();
        } catch (Exception e) {
            log.debug("团队使用统计上报轮次失败（静默）: {}", e.toString());
        }
    }

    /**
     * 设置页「立即上报」：与 {@link #sync()} 同一条路，但把结果<b>如实</b>回给调用方——
     * 用户主动点的按钮必须能看见失败原因，静默是给后台轮次准备的，不是给它准备的。
     *
     * @return {@code {uploaded, skipped, reason?, lastUploadAt}}
     */
    public Map<String, Object> uploadNow() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int uploaded = run();
            result.put("uploaded", uploaded);
            result.put("skipped", false);
        } catch (SkippedException e) {
            result.put("uploaded", 0);
            result.put("skipped", true);
            result.put("reason", e.reason);
        }
        result.put("lastUploadAt", settings.lastUploadAt());
        return result;
    }

    /**
     * 这台机器上「共享使用统计」这个开关有没有意义。server 模式（团队案件库 / 插件云实例）下
     * 恒 false——那里账户是机器级的、使用数据是每个租户各自的，开关打开也不会上报。
     * <b>由后端如实下发，不让前端靠 host 存在与否猜</b>：猜错的后果是给一个永远不生效的开关。
     */
    public boolean sharingAvailable() {
        return localIdentityService.isLocalMode();
    }

    /** 跳过原因（机器可读），供前端给出对应的下一步。不是错误，所以不进 GlobalExceptionHandler。 */
    static final class SkippedException extends RuntimeException {
        final String reason;
        SkippedException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** @return 本轮成功上报的天数 */
    private int run() {
        if (!settings.enabled()) throw new SkippedException("disabled");
        if (!localIdentityService.isLocalMode()) throw new SkippedException("not_local_mode");
        if (!accountService.isConnected()) throw new SkippedException("not_connected");
        if (!hasTeam()) throw new SkippedException("no_team");

        Long userId = localIdentityService.localUserId();
        LocalDate today = LocalDate.now();
        int uploaded = 0;
        // 从最早的缺口往今天推：管理者第一次打开开关时，看到的应该是一段完整的历史，
        // 而不是先冒出昨天、过一天再冒出前天
        for (int i = BACKFILL_DAYS; i >= 1; i--) {
            LocalDate date = today.minusDays(i);
            if (settings.alreadyUploaded(date)) continue;
            Map<String, Object> payload = rollupService.rollupFor(date, userId);
            if (isEmptyDay(payload)) {
                // 那天根本没开过应用。标记成已传，免得每轮都重算一遍空数据
                settings.markUploaded(date);
                continue;
            }
            accountService.uploadTeamUsage(rollupService.toJson(payload));
            settings.markUploaded(date);
            uploaded++;
        }
        return uploaded;
    }

    /**
     * 团队判定：官网 {@code GET /api/account/team} 的 {@code team} 字段。
     * 顺手把「共享项目名」缓存到本机——聚合时要靠它决定带不带项目名，
     * 而聚合跑在后台线程里，不该临时去打一次网络。
     */
    private boolean hasTeam() {
        Map<String, Object> body = accountService.fetchTeam();
        if (!(body.get("team") instanceof Map<?, ?> team)) return false;
        teamSettingsCache.remember(team);
        return true;
    }

    /**
     * 全零的一天不发。判据是「六个计数全为 0 且没有任何项目行」——
     * 只判 activeMinutes 会漏掉「开了应用聊了几句但没触发工作段」的日子。
     */
    private static boolean isEmptyDay(Map<String, Object> payload) {
        for (String key : new String[]{"appStarts", "activeMinutes", "aiTurns", "aiToolCalls",
                "agentEditActions", "manualEditActions", "projectsCreated"}) {
            if (payload.get(key) instanceof Number n && n.longValue() > 0) return false;
        }
        return !(payload.get("projects") instanceof java.util.List<?> list) || list.isEmpty();
    }
}
