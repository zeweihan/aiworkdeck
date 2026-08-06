package com.checkba.service.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstallIdentityServiceTest {

    @TempDir
    Path dir;

    @Test
    void installIdIsGeneratedOncePersistedAndStable() {
        InstallIdentityService a = new InstallIdentityService(dir.toString());
        String id1 = a.installId();
        assertNotNull(id1);
        assertTrue(Files.exists(dir.resolve("install-id")));

        // 新实例（模拟重启）读回同一 ID
        InstallIdentityService b = new InstallIdentityService(dir.toString());
        assertEquals(id1, b.installId());
    }

    @Test
    void convKeyIsStableDerivedAndOpaque() {
        InstallIdentityService svc = new InstallIdentityService(dir.toString());
        String conv = "conv-1754460000000";
        String k1 = svc.convKey(conv);
        String k2 = svc.convKey(conv);
        assertEquals(k1, k2);
        assertEquals(16, k1.length());
        // 不得泄露原始 id 的任何子串（时间戳）
        assertFalse(k1.contains("1754460000000"));
        // 不同会话不同键
        assertNotEquals(k1, svc.convKey("conv-1754460000001"));
        // 重启后（同 secret 文件）仍稳定
        assertEquals(k1, new InstallIdentityService(dir.toString()).convKey(conv));
        // 空输入
        assertNull(svc.convKey(null));
    }

    @Test
    void differentInstallsProduceDifferentConvKeys() throws Exception {
        Path other = Files.createDirectories(dir.resolve("other"));
        String conv = "conv-1754460000000";
        assertNotEquals(
                new InstallIdentityService(dir.toString()).convKey(conv),
                new InstallIdentityService(other.toString()).convKey(conv));
    }
}
