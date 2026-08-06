package com.checkba.service.telemetry;

import com.checkba.service.SystemSettingService;
import org.springframework.stereotype.Service;

/**
 * 埋点上报开关（设计 §5.7）。语义：只控制「出本机」，本地账本永远记录。
 * - telemetry.rollup.enabled 缺省 true：分享匿名日聚合计数
 * - telemetry.events.enabled 缺省 false：分享脱敏事件流（Tier 2）
 */
@Service
public class TelemetrySettings {

    public static final String KEY_ROLLUP = "telemetry.rollup.enabled";
    public static final String KEY_EVENTS = "telemetry.events.enabled";

    private final SystemSettingService settings;

    public TelemetrySettings(SystemSettingService settings) {
        this.settings = settings;
    }

    public boolean rollupEnabled() {
        return Boolean.parseBoolean(settings.get(KEY_ROLLUP, "true"));
    }

    public boolean eventsEnabled() {
        return Boolean.parseBoolean(settings.get(KEY_EVENTS, "false"));
    }

    public void setRollupEnabled(boolean v) {
        settings.set(KEY_ROLLUP, Boolean.toString(v));
    }

    public void setEventsEnabled(boolean v) {
        settings.set(KEY_EVENTS, Boolean.toString(v));
    }
}
