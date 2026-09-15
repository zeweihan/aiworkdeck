// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.team;

import com.checkba.service.SystemSettingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 团队使用统计的本机开关与上报台账（dev-board#496）。
 *
 * <p><b>与匿名 telemetry 的开关物理分离，不复用 {@code TelemetrySettings}。</b>
 * 那一条通道的公开承诺是「安装标识与账户无关」（legal/PRIVACY.md、README、官网建表注释三处），
 * 团队统计恰恰相反——它带 {@code Bearer awdk_}、按账户落库。两个开关合并等于让用户
 * 在一个勾选框里同时同意两件性质完全不同的事。
 *
 * <p>默认 <b>关</b>：律所的使用数据要不要给管理者看，只能由机器主人显式打开。
 */
@Service
public class TeamUsageSettings {

    /** 总开关。默认 false——不打开就一个字节都不出本机。 */
    public static final String KEY_ENABLED = "team.usage.enabled";
    /** 最近一次成功上报的时刻（ISO-8601），供设置页展示。 */
    public static final String KEY_LAST_UPLOAD_AT = "team.usage.lastUploadAt";
    /** 已确认上报的日期清单（逗号分隔的 ISO 日期），用于补传时跳过已传的日子。 */
    public static final String KEY_UPLOADED_DATES = "team.usage.uploadedDates";

    /** 台账保留窗口：与补传窗口同为 30 天，超窗的日期会被裁掉，避免这一行无限增长。 */
    static final int RETENTION_DAYS = 30;

    private final SystemSettingService settings;

    public TeamUsageSettings(SystemSettingService settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.get(KEY_ENABLED, "false"));
    }

    public void setEnabled(boolean value) {
        settings.set(KEY_ENABLED, Boolean.toString(value));
    }

    /** 空串表示从未成功上报过（前端显示「尚未上报」，不要顶成某个时间）。 */
    public String lastUploadAt() {
        return settings.get(KEY_LAST_UPLOAD_AT, "");
    }

    public void markUploaded(LocalDate date) {
        Set<String> kept = uploadedDates().stream()
                .filter(d -> !d.isBefore(LocalDate.now().minusDays(RETENTION_DAYS)))
                .map(LocalDate::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        kept.add(date.toString());
        settings.set(KEY_UPLOADED_DATES, String.join(",", kept));
        settings.set(KEY_LAST_UPLOAD_AT, Instant.now().toString());
    }

    public boolean alreadyUploaded(LocalDate date) {
        return uploadedDates().contains(date);
    }

    /** 断开/换账户时调用：台账是「已经传给<b>那个</b>账户」的记录，换了人必须从头传。 */
    public void resetLedger() {
        settings.set(KEY_UPLOADED_DATES, "");
        settings.set(KEY_LAST_UPLOAD_AT, "");
    }

    Set<LocalDate> uploadedDates() {
        String raw = settings.get(KEY_UPLOADED_DATES, "");
        Set<LocalDate> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            try {
                out.add(LocalDate.parse(token));
            } catch (Exception ignored) {
                // 手工改坏的行不该让整条上报链挂掉，跳过即可（最坏结果是那天重传一次，幂等）
            }
        }
        return out;
    }
}
