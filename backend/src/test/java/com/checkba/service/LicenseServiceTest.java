package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定解锁门契约：
 * - 非 local-mode（团队服务器）恒 {"unlocked":true,"mode":"account","plan":"paid"}，不设解锁门；
 * - local-mode 默认未解锁；试用码激活→trial 并落盘 ~/.aiworkdeck/license.json；
 * - account 模式断网 30 天宽限，超期回落未解锁并提示联网复验；
 * - deactivate 清除授权状态。
 */
class LicenseServiceTest {

    private static final String VALID_TRIAL_CODE =
            "AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y"
                    + "-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK";

    @TempDir
    Path tempDir;

    /** base-url 指向本机必拒端口，account Key 在线校验在测试里恒为「无法连接」。 */
    private LicenseService service(boolean localMode) {
        return new LicenseService(localMode,
                com.checkba.service.site.SiteProfileService.pinnedTo("https://127.0.0.1:1"), tempDir.toString());
    }

    @Test
    void serverModeAlwaysUnlockedWithoutGate() {
        LicenseService svc = service(false);
        Map<String, Object> status = svc.status();
        assertEquals(true, status.get("unlocked"));
        assertEquals("account", status.get("mode"));
        assertEquals("paid", status.get("plan"));
        // 激活/取消在团队服务器上都是无操作，且不落盘
        assertEquals(true, svc.activate("whatever").get("unlocked"));
        assertEquals(true, svc.deactivate().get("unlocked"));
        assertFalse(Files.exists(tempDir.resolve("license.json")));
    }

    @Test
    void localModeDefaultsLocked() {
        Map<String, Object> status = service(true).status();
        assertEquals(false, status.get("unlocked"));
        assertEquals("none", status.get("mode"));
        assertEquals("none", status.get("plan"));
    }

    @Test
    void trialActivationUnlocksAndPersists() {
        LicenseService svc = service(true);
        Map<String, Object> result = svc.activate(VALID_TRIAL_CODE);
        assertEquals(true, result.get("unlocked"), "有效试用码应解锁: " + result.get("message"));
        assertEquals("trial", result.get("mode"));
        assertTrue(Files.exists(tempDir.resolve("license.json")), "授权状态应落盘 license.json");

        // 新实例（模拟重启）从盘上恢复状态
        Map<String, Object> status = service(true).status();
        assertEquals(true, status.get("unlocked"));
        assertEquals("trial", status.get("mode"));
        assertEquals("trial", status.get("plan"));
        assertNotNull(status.get("activatedAt"), "status 必须带激活时间（userprofile 授权卡片展示用）");
    }

    @Test
    void invalidTrialCodeRejectedWithChineseMessage() {
        LicenseService svc = service(true);
        Map<String, Object> result = svc.activate("AWD-T-XXXX-XXXX");
        assertEquals(false, result.get("unlocked"));
        assertTrue(String.valueOf(result.get("message")).contains("试用码"));
    }

    @Test
    void accountKeyUnreachableServerGivesNetworkError() {
        LicenseService svc = service(true);
        Map<String, Object> result = svc.activate("awdk_deadbeef");
        assertEquals(false, result.get("unlocked"));
        assertTrue(String.valueOf(result.get("message")).contains("网络"),
                "断网应给中文网络错误: " + result.get("message"));
    }

    @Test
    void deactivateClearsState() {
        LicenseService svc = service(true);
        svc.activate(VALID_TRIAL_CODE);
        Map<String, Object> result = svc.deactivate();
        assertEquals(false, result.get("unlocked"));
        assertEquals("none", result.get("mode"));
        assertEquals("none", service(true).status().get("mode"));
    }

    @Test
    void accountModeWithinGraceStaysUnlocked() throws Exception {
        writeState("account", Instant.now().minus(Duration.ofDays(10)));
        Map<String, Object> status = service(true).status();
        assertEquals(true, status.get("unlocked"));
        assertEquals("account", status.get("mode"));
        assertEquals("paid", status.get("plan"));
        assertNotNull(status.get("activatedAt"), "status 必须带激活时间（userprofile 授权卡片展示用）");
    }

    @Test
    void accountModeBeyondGraceFallsBackLocked() throws Exception {
        writeState("account", Instant.now().minus(Duration.ofDays(31)));
        Map<String, Object> status = service(true).status();
        assertEquals(false, status.get("unlocked"));
        assertEquals("account", status.get("mode"));
        assertTrue(String.valueOf(status.get("message")).contains("联网"),
                "超期应提示需联网重新验证: " + status.get("message"));
    }

    @Test
    void corruptLicenseFileTreatedAsLocked() throws Exception {
        Files.writeString(tempDir.resolve("license.json"), "{not json", StandardCharsets.UTF_8);
        Map<String, Object> status = service(true).status();
        assertEquals(false, status.get("unlocked"));
        assertEquals("none", status.get("mode"));
    }

    // ==================== 2026-08-05 安全审查 F5 ====================

    @Test
    void licenseFileIsOwnerOnly() throws Exception {
        LicenseService svc = service(true);
        svc.activate(VALID_TRIAL_CODE);
        Path file = tempDir.resolve("license.json");
        var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "非 POSIX 文件系统跳过");
        // license.json 存明文 awdk_ 账户 Key，默认 umask 下是 0644
        assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void httpAccountBaseUrlIsRejected() {
        // 明文 awdk_ Key 不允许走未加密通道；默认值本就是 https，配成 http 属明确错误配置
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.checkba.service.site.SiteProfileService.pinnedTo("http://www.aiworkdeck.com"));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }

    private void writeState(String mode, Instant lastVerifiedAt) throws Exception {
        String json = "{\"mode\":\"" + mode + "\",\"code\":\"awdk_test\",\"activatedAt\":\""
                + lastVerifiedAt + "\",\"lastVerifiedAt\":\"" + lastVerifiedAt + "\"}";
        Files.writeString(tempDir.resolve("license.json"), json, StandardCharsets.UTF_8);
    }
}
