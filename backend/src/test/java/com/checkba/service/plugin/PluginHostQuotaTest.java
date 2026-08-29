package com.checkba.service.plugin;

import com.checkba.plugin.api.HostQuotaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginHostQuotaTest {

    @Test
    @DisplayName("ai.request 窗口独立计数：10 次/分钟，超限抛 HostQuotaException，不影响工具窗口")
    void aiWindowIsIndependent() {
        PluginHostQuota quota = new PluginHostQuota();
        for (int i = 0; i < PluginHostQuota.DEFAULT_AI_LIMIT_PER_MINUTE; i++) {
            quota.acquireAi("p1");
        }
        assertThrows(HostQuotaException.class, () -> quota.acquireAi("p1"));
        // 工具窗口不受 ai 窗口影响；别的插件的 ai 窗口也不受影响
        assertDoesNotThrow(() -> quota.acquire("p1"));
        assertDoesNotThrow(() -> quota.acquireAi("p2"));
    }
}
