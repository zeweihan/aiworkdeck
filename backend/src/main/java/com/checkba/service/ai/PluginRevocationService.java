package com.checkba.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台封禁列表同步（docs/PLUGIN_DISTRIBUTION.md §8）。
 *
 * 启动时与每 24 小时从注册表拉一次；命中的已安装插件强制禁用。
 * 这是插件出问题后唯一的远程处置手段——VS Code 与 JetBrains 也都依赖同类机制。
 *
 * 注册表不可达时静默跳过：封禁同步失败不应影响本地功能，下次调度会重试。
 */
@Service
@Slf4j
public class PluginRevocationService {

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    private final PluginMarketService marketService;
    private final PluginService pluginService;

    public PluginRevocationService(PluginMarketService marketService, PluginService pluginService) {
        this.marketService = marketService;
        this.pluginService = pluginService;
    }

    @PostConstruct
    public void onStartup() {
        // 启动时异步拉一次，避免注册表慢响应拖住应用启动（编译目标 Java 17，不用虚拟线程）
        Thread t = new Thread(this::sync, "plugin-revocation-init");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = DAY_MS, initialDelay = DAY_MS)
    public void scheduledSync() {
        sync();
    }

    /** 拉取并应用封禁列表；返回本次新增被禁用的插件 id（供手动触发时回显） */
    public List<String> sync() {
        try {
            List<PluginMarketService.RevokedPlugin> revoked = marketService.fetchRevoked();
            Map<String, String> byId = new HashMap<>();
            for (PluginMarketService.RevokedPlugin r : revoked) {
                if (r.id() == null) continue;
                // version 为 "*" 或与本地安装版本一致时才算命中；这里按 id 粒度处理，
                // 因为本地同一 id 只会存在一个版本
                byId.put(r.id(), r.reason() == null ? "平台已下架" : r.reason());
            }
            List<String> disabled = pluginService.applyRevocations(byId);
            if (!disabled.isEmpty()) {
                log.warn("Revocation sync disabled {} installed plugin(s): {}", disabled.size(), disabled);
            } else {
                log.info("Revocation sync done: {} entries, none installed locally", byId.size());
            }
            return disabled;
        } catch (Exception e) {
            log.warn("Revocation sync skipped: {}", e.getMessage());
            return List.of();
        }
    }
}
