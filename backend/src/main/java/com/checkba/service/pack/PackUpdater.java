// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.pack;

import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 已装资源包的版本追新（规范 docs/NATIVE_PACK_DISTRIBUTION.md §5.1）。
 *
 * <p>解决的是 dev-board#477 那个真实故障：**应用更新不等于 native pack 更新**。
 * 用户的桌面端一路升到最新，本机 litviz 却还停在 2026-08-20 的 1.0.1——新加的
 * {@code timeline} 子命令在旧引擎里根本不存在，所有材料都在 argparse 处 exit 2。
 * pack 装好之后此前**没有任何**代码路径会再看一眼 registry：
 * {@link PackAutoInstaller} 只在「资源全链缺失」时补装，装上了就再也不管版本。
 *
 * <p>节奏照 {@code TelemetryUploadService}：启动后延迟几十秒（别和后端启动抢 CPU 与
 * 网络），此后每 24h 一次。
 *
 * <p>失败一律静默 WARN、保持旧版本：追新是后台动作，用户没点任何按钮，
 * 不该因为一次镜像抖动就弹窗或把功能变哑。真正的换版发生在
 * {@link NativePackService#upgradeIfNewer}（走完整安装事务，指针最后才切），
 * 半路失败时本机仍然是升级前那一版。
 */
@Component
@Slf4j
public class PackUpdater {

    /** 启动后延迟多久开始检查。比 {@link PackAutoInstaller} 的 10s 晚：补装缺失资源比追新急。 */
    private static final long INITIAL_DELAY_SECONDS = 45;

    /** 检查周期，与封禁表同步同一节奏 */
    private static final long PERIOD_SECONDS = 24 * 60 * 60L;

    private final PackProperties props;
    private final NativePackService packService;
    private final SkillRegistry skillRegistry;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "native-pack-updater");
                t.setDaemon(true);
                return t;
            });

    public PackUpdater(PackProperties props, NativePackService packService, SkillRegistry skillRegistry) {
        this.props = props;
        this.packService = packService;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    public void schedule() {
        if (!props.isEnabled() || !props.isAutoUpgrade()) return;
        scheduler.scheduleWithFixedDelay(this::checkAndUpgrade,
                INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 对每个「已装好且处于启用状态」的 pack 比对一次 registry 版本，有新版就换上。
     *
     * @return 本轮真正升级过的 packId
     */
    public List<String> checkAndUpgrade() {
        List<String> upgraded = new ArrayList<>();
        if (!props.isEnabled() || !props.isAutoUpgrade()) return upgraded;
        Set<String> disabled = disabledPackIds();
        for (String packId : packService.installedPackIds()) {
            // isReady 已经排掉「半成品」与「被平台封禁」两种：封禁的包要的是卸载，不是追新
            if (!packService.isReady(packId)) continue;
            if (disabled.contains(packId)) continue;
            try {
                packService.upgradeIfNewer(packId).ifPresent(v -> upgraded.add(packId));
            } catch (Exception e) {
                // 镜像不可达、签名不符、下载中断……一律保持旧版本，下一轮再说
                log.warn("资源包 {} 追新失败，保持当前版本: {}", packId, e.getMessage());
            }
        }
        return upgraded;
    }

    /**
     * 「被用户关掉的功能对应的 pack」：某个 skill 声明了 {@code requires_pack} 且它被停用了，
     * 就不为它耗流量追新（与 {@link PackAutoInstaller} 只给已启用 skill 补装同一口径）。
     *
     * <p>只要有<b>任何一个</b>启用中的 skill 指着它，就算启用——一个 pack 可以被多个消费方
     * 共用。没有任何 skill 声明它的 pack（三方插件带的那类）按启用处理：它的启停记在
     * 插件那边，这里无从判断，不该因为看不见就停止追新。
     */
    private Set<String> disabledPackIds() {
        Set<String> disabled = new HashSet<>();
        Set<String> enabled = new HashSet<>();
        for (SkillDefinition skill : skillRegistry.getSkills()) {
            String packId = skill.getRequiresPack();
            if (packId == null || packId.isBlank()) continue;
            if (skillRegistry.isEnabled(skill.getId())) {
                enabled.add(packId);
            } else {
                disabled.add(packId);
            }
        }
        disabled.removeAll(enabled);
        return disabled;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
