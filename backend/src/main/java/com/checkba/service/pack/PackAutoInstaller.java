package com.checkba.service.pack;

import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 升级后的资源缺口自动补下载（规范 docs/NATIVE_PACK_DISTRIBUTION.md §5 / §13-2）。
 *
 * <p>解决的是这个场景：老用户升级到「资源已从安装包里摘除」的大版本，Resources 里的
 * 随包资源随 .app 替换消失，而本地还没有 pack——于是「skill 已启用」与「资源缺失」并存。
 * 用户当初打开这个功能就是在表达要它，升级不该让它变哑。
 *
 * <p>判据是 {@link NativePackService#resourceReady}（pack 已装 <b>或</b> 随包内置在场），
 * 所以随包资源还在的老版本一个字节都不会下。
 */
@Component
@Slf4j
public class PackAutoInstaller {

    /** 启动后延迟多久开始检查：别和后端启动抢 CPU 与网络 */
    private static final long DELAY_SECONDS = 10;

    private final PackProperties props;
    private final NativePackService packService;
    private final SkillRegistry skillRegistry;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "native-pack-auto-install");
                t.setDaemon(true);
                return t;
            });

    public PackAutoInstaller(PackProperties props, NativePackService packService, SkillRegistry skillRegistry) {
        this.props = props;
        this.packService = packService;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    public void scheduleCheck() {
        if (!props.isEnabled()) return;
        scheduler.schedule(this::checkAndInstall, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** 对每个「声明了 requires_pack 且已启用、但资源不在场」的 skill 触发一次安装。 */
    public List<String> checkAndInstall() {
        if (!props.isEnabled()) return List.of();
        List<String> triggered = new ArrayList<>();
        for (SkillDefinition skill : skillRegistry.getSkills()) {
            String packId = skill.getRequiresPack();
            if (packId == null || packId.isBlank()) continue;
            if (!skillRegistry.isEnabled(skill.getId())) continue;
            if (packService.resourceReady(packId)) continue;
            try {
                packService.installAsync(packId);
                triggered.add(packId);
                log.info("Skill '{}' is enabled but pack '{}' is missing; auto-install triggered",
                        skill.getId(), packId);
            } catch (Exception e) {
                log.warn("资源包 {} 自动补下载未能启动: {}", packId, e.getMessage());
            }
        }
        return triggered;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
