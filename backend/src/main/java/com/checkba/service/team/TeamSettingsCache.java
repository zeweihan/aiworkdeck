package com.checkba.service.team;

import com.checkba.service.SystemSettingService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 团队设置在本机的只读缓存（dev-board#496）。当前只缓存一件事：
 * 团队是否开了「共享项目名」。
 *
 * <p>为什么要缓存：日聚合跑在后台线程里，它要据此决定项目行带不带项目名。
 * 让它临时打一次官网，等于把「能不能上报」和「官网此刻通不通」绑在一起，
 * 而且失败时没有安全的默认值可选——所以改成「每次拉团队信息时顺手记下」，
 * 聚合侧只读本机。
 *
 * <p><b>读不到一律按 true</b>：维护者裁决（dev-board#496，2026-09-08）项目名默认随统计
 * 上云，管理者可在团队设置里关闭。权威值始终在官网，本机这份只是给聚合用的快照；
 * 一旦拉到权威值就会被 {@link #remember} 覆盖，这个默认值只在从没拉到过时生效。
 */
@Service
public class TeamSettingsCache {

    /** 团队是否允许项目名随日聚合上云。默认 true（管理者可关）。 */
    public static final String KEY_SHARE_PROJECT_NAMES = "team.shareProjectNames";

    private final SystemSettingService settings;

    public TeamSettingsCache(SystemSettingService settings) {
        this.settings = settings;
    }

    public boolean shareProjectNames() {
        return Boolean.parseBoolean(settings.get(KEY_SHARE_PROJECT_NAMES, "true"));
    }

    /** 从官网返回的 team 对象里记下需要缓存的字段。字段缺失时按 true 落，不保留旧值。 */
    public void remember(Map<?, ?> team) {
        boolean share = team == null || !Boolean.FALSE.equals(team.get("shareProjectNames"));
        settings.set(KEY_SHARE_PROJECT_NAMES, Boolean.toString(share));
    }

    /** 断开账户 / 退出团队后清回默认值。 */
    public void clear() {
        settings.set(KEY_SHARE_PROJECT_NAMES, "true");
    }
}
