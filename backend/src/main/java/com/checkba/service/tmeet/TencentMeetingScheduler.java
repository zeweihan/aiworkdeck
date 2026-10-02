// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.model.entity.TencentMeetingSyncConfig;
import com.checkba.repository.TencentMeetingSyncConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 腾讯会议定时同步调度器：
 * 周期性检测启用了自动同步的用户配置，并按设定的时间间隔执行静默同步。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TencentMeetingScheduler {

    private final TencentMeetingSyncConfigRepository configRepository;
    private final TencentMeetingService tencentMeetingService;

    // 每 60 秒检查一次是否到达同步周期
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void runScheduledSync() {
        List<TencentMeetingSyncConfig> configs = configRepository.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (TencentMeetingSyncConfig cfg : configs) {
            if (!Boolean.TRUE.equals(cfg.getAutoSync())) {
                continue;
            }

            int intervalMinutes = cfg.getSyncIntervalMinutes() != null && cfg.getSyncIntervalMinutes() > 0
                    ? cfg.getSyncIntervalMinutes() : 60;

            boolean due = cfg.getLastSyncAt() == null
                    || cfg.getLastSyncAt().plusMinutes(intervalMinutes).isBefore(now);

            if (due && !"RUNNING".equals(cfg.getLastSyncStatus())) {
                try {
                    log.info("[TmeetScheduler] 触发用户 {} 的定时同步任务 (间隔 {} 分钟)", cfg.getUserId(), intervalMinutes);
                    tencentMeetingService.syncMeetings(cfg.getUserId(), null, false);
                } catch (Exception e) {
                    log.warn("[TmeetScheduler] 定时同步执行跳过或失败 (userId={}): {}", cfg.getUserId(), e.getMessage());
                }
            }
        }
    }
}
