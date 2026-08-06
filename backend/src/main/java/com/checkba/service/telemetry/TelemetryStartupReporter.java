package com.checkba.service.telemetry;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 启动事件：app.start（平台与 profile 枚举值，无任何设备细节） */
@Component
public class TelemetryStartupReporter {

    private final TelemetryService telemetry;
    private final Environment environment;

    public TelemetryStartupReporter(TelemetryService telemetry, Environment environment) {
        this.telemetry = telemetry;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String platform = normalizeOs(System.getProperty("os.name", "unknown"))
                + "-" + System.getProperty("os.arch", "unknown");
        String[] profiles = environment.getActiveProfiles();
        telemetry.record("app.start", Map.of(
                "platform", platform,
                "profile", profiles.length == 0 ? "default" : profiles[0]));
    }

    private static String normalizeOs(String osName) {
        String lower = osName.toLowerCase();
        if (lower.contains("mac")) return "darwin";
        if (lower.contains("win")) return "win32";
        if (lower.contains("linux")) return "linux";
        return "other";
    }
}
