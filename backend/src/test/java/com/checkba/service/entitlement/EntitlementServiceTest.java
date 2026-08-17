package com.checkba.service.entitlement;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 锁定权益合并与宽限契约（Spec §6）：
 * - 来源是并集：本地票据（试用码解锁 → app.unlocked）∪ 账户同步结果；
 * - 账户型权益断网 30 天宽限，**超期回落为「未拥有」**（不是保持拥有，否则永久离线=永久买断）；
 * - 本地票据不吃宽限（试用码离线验签，本就不需要联网）；
 * - 刷新失败不抛异常（启动期异步刷新绝不能拖垮启动）。
 */
class EntitlementServiceTest {

    @TempDir
    Path tempDir;

    private LicenseService licenseService;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        accountService = mock(AccountService.class);
        when(licenseService.status()).thenReturn(Map.of("unlocked", false, "mode", "none"));
        when(accountService.isConnected()).thenReturn(false);
    }

    private EntitlementService service() {
        return new EntitlementService(licenseService, accountService, tempDir.toString());
    }

    /** 账户型权益只在账户仍连接时生效，所以写缓存的用例都得先把连接立起来。 */
    private void writeCache(Instant syncedAt, String... features) throws Exception {
        when(accountService.isConnected()).thenReturn(true);
        String list = String.join(",", java.util.Arrays.stream(features).map(f -> "\"" + f + "\"").toList());
        Files.writeString(tempDir.resolve("entitlements.json"),
                "{\"syncedAt\":\"" + syncedAt + "\",\"features\":[" + list + "]}", StandardCharsets.UTF_8);
    }

    /** 缓存陈旧时快照会顺手起一次后台刷新；单测里让它确定性失败，免得干扰断言。 */
    private void networkDown() {
        when(accountService.fetchEntitlements())
                .thenThrow(new AccountException(AccountException.Kind.NETWORK, "无法连接 AI WorkDeck 服务器"));
    }

    // ==================== 合并 ====================

    @Test
    @DisplayName("全新安装：什么都没有")
    void freshInstallHasNothing() {
        EntitlementService svc = service();
        assertFalse(svc.isEnabled(FeatureCatalog.APP_UNLOCKED));
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertFalse(svc.isEnabled(null));
        assertFalse(svc.isEnabled(""));
    }

    @Test
    @DisplayName("本地票据：试用码解锁只给 app.unlocked，不给付费 SKU")
    void localTicketOnlyUnlocksApp() {
        when(licenseService.status()).thenReturn(Map.of("unlocked", true, "mode", "trial"));
        EntitlementService svc = service();
        assertTrue(svc.isEnabled(FeatureCatalog.APP_UNLOCKED));
        // 试用版同样受免费额度约束（Spec §5）
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertFalse(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
    }

    @Test
    @DisplayName("并集：本地票据 ∪ 账户同步，两边各自生效")
    void sourcesAreUnioned() throws Exception {
        when(licenseService.status()).thenReturn(Map.of("unlocked", true, "mode", "trial"));
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED, "skill:due-diligence");

        EntitlementService svc = service();
        assertTrue(svc.isEnabled(FeatureCatalog.APP_UNLOCKED), "来自本地票据");
        assertTrue(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED), "来自账户同步");
        assertTrue(svc.isEnabled("skill:due-diligence"), "动态权益（付费 Skill）也走同一出口");
        assertFalse(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
    }

    @Test
    @DisplayName("账户同步也能给 app.unlocked（账户 Key 即正式版）")
    void accountCanGrantAppUnlocked() throws Exception {
        writeCache(Instant.now(), FeatureCatalog.APP_UNLOCKED);
        assertTrue(service().isEnabled(FeatureCatalog.APP_UNLOCKED));
    }

    // ==================== 宽限 ====================

    @Test
    @DisplayName("29 天未同步：账户型权益仍在宽限内")
    void withinGraceStillOwned() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(29)), FeatureCatalog.STAGE_UNLIMITED);
        networkDown();
        EntitlementService svc = service();
        assertTrue(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
        assertEquals(false, svc.snapshot().get("stale"));
    }

    @Test
    @DisplayName("31 天未同步：账户型权益整体回落为未拥有，本地票据不受影响")
    void beyondGraceFallsBackToNotOwned() throws Exception {
        when(licenseService.status()).thenReturn(Map.of("unlocked", true, "mode", "trial"));
        writeCache(Instant.now().minus(Duration.ofDays(31)),
                FeatureCatalog.STAGE_UNLIMITED, FeatureCatalog.CLIPBOARD_UNLIMITED);
        networkDown();

        EntitlementService svc = service();
        assertFalse(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertTrue(svc.isEnabled(FeatureCatalog.APP_UNLOCKED), "本地票据是离线验签的，不吃宽限");
        assertEquals(true, svc.snapshot().get("stale"));
    }

    @Test
    @DisplayName("缓存文件损坏：按无账户权益处理，不抛异常")
    void corruptCacheTreatedAsEmpty() throws Exception {
        when(accountService.isConnected()).thenReturn(true);
        networkDown();
        Files.writeString(tempDir.resolve("entitlements.json"), "{not json", StandardCharsets.UTF_8);
        assertFalse(service().isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
    }

    // ==================== 刷新与快照 ====================

    @Test
    @DisplayName("刷新：写入缓存并即时生效")
    void refreshWritesCache() {
        when(accountService.isConnected()).thenReturn(true);
        when(accountService.fetchEntitlements()).thenReturn(List.of(
                Map.of("feature", FeatureCatalog.CLIPBOARD_UNLIMITED, "orderId", "o1"),
                Map.of("feature", "plugin:shareholder-meeting", "orderId", "o2")));

        EntitlementService svc = service();
        assertTrue(svc.refreshQuietly());
        assertTrue(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertTrue(svc.isEnabled("plugin:shareholder-meeting"));
    }

    @Test
    @DisplayName("刷新失败（断网）：吞掉异常并保留既有缓存，走宽限")
    void refreshFailureKeepsCache() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(2)), FeatureCatalog.STAGE_UNLIMITED);
        networkDown();

        EntitlementService svc = service();
        assertFalse(svc.refreshQuietly());
        assertTrue(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED), "断网不该把已购权益吃掉");
    }

    @Test
    @DisplayName("官网明确拒绝（Key 已吊销）：立刻清缓存，不吃 30 天宽限")
    void unauthorizedClearsCacheImmediately() throws Exception {
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED, "skill:due-diligence");
        when(accountService.fetchEntitlements()).thenThrow(
                new AccountException(AccountException.Kind.UNAUTHORIZED, "账户 Key 无效或已被撤销"));

        EntitlementService svc = service();
        assertTrue(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED), "前提：吊销前是有的");
        assertFalse(svc.refreshQuietly());
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED),
                "吊销 Key 后付费功能不能再撑 30 天");
        assertFalse(svc.isEnabled("skill:due-diligence"));
    }

    @Test
    @DisplayName("未连接账户：即便本地有一份 entitlements.json 也不生效")
    void cacheIgnoredWhenAccountNotConnected() throws Exception {
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED, FeatureCatalog.STAGE_UNLIMITED);
        when(accountService.isConnected()).thenReturn(false);

        EntitlementService svc = service();
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertFalse(svc.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
    }

    @Test
    @DisplayName("缓存陈旧：快照顺手起一次后台刷新（长期不重启的实例也能拿到新购权益）")
    void staleSnapshotTriggersRefresh() throws Exception {
        writeCache(Instant.now().minus(Duration.ofHours(2)), FeatureCatalog.STAGE_UNLIMITED);
        when(accountService.fetchEntitlements()).thenReturn(List.of(
                Map.of("feature", FeatureCatalog.STAGE_UNLIMITED),
                Map.of("feature", FeatureCatalog.CLIPBOARD_UNLIMITED)));

        EntitlementService svc = service();
        svc.snapshot();
        // 刷新是后台线程，给它一点时间落盘
        for (int i = 0; i < 50 && !svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED); i++) {
            Thread.sleep(20);
        }
        assertTrue(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED),
                "陈旧缓存应触发同步，官网上刚买的功能不该等到重启才生效");
    }

    @Test
    @DisplayName("缓存新鲜：快照不发请求（避免设置页每次打开都打一次官网）")
    void freshSnapshotDoesNotRefresh() throws Exception {
        writeCache(Instant.now(), FeatureCatalog.STAGE_UNLIMITED);

        service().snapshot();
        Thread.sleep(50);
        verify(accountService, never()).fetchEntitlements();
    }

    @Test
    @DisplayName("未连接账户：刷新直接跳过，不发请求")
    void refreshSkippedWhenNotConnected() {
        assertFalse(service().refreshQuietly());
        verify(accountService, never()).fetchEntitlements();
    }

    @Test
    @DisplayName("断开连接：账户型权益立刻清空，本地票据保留")
    void disconnectClearsAccountFeaturesOnly() throws Exception {
        when(licenseService.status()).thenReturn(Map.of("unlocked", true, "mode", "trial"));
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED);

        EntitlementService svc = service();
        svc.clearAccountCache();
        assertFalse(svc.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED));
        assertTrue(svc.isEnabled(FeatureCatalog.APP_UNLOCKED));
    }

    @Test
    @DisplayName("快照 features 只含已拥有的：前端把「出现在列表里」当已拥有，混进未拥有项等于全解锁")
    @SuppressWarnings("unchecked")
    void snapshotFeaturesContainsOwnedOnly() throws Exception {
        when(licenseService.status()).thenReturn(Map.of("unlocked", true, "mode", "trial"));
        when(accountService.isConnected()).thenReturn(true);
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED, "skill:due-diligence");

        Map<String, Object> snapshot = service().snapshot();
        List<Map<String, Object>> owned = (List<Map<String, Object>>) snapshot.get("features");

        assertEquals(
                List.of(FeatureCatalog.APP_UNLOCKED, FeatureCatalog.CLIPBOARD_UNLIMITED, "skill:due-diligence"),
                owned.stream().map(f -> f.get("feature")).toList());
        owned.forEach(f -> assertEquals(true, f.get("enabled"), f + " 出现在 features 里就必须是已拥有"));
        assertEquals("local", owned.get(0).get("source"));
        assertEquals("account", owned.get(1).get("source"));
        assertEquals(true, snapshot.get("accountConnected"));
    }

    @Test
    @DisplayName("快照 catalog 是目录全集带 enabled 标志（设置页「已有/去购买」用）")
    @SuppressWarnings("unchecked")
    void snapshotCatalogCoversWholeDirectory() throws Exception {
        writeCache(Instant.now(), FeatureCatalog.CLIPBOARD_UNLIMITED);

        List<Map<String, Object>> catalog =
                (List<Map<String, Object>>) service().snapshot().get("catalog");
        assertEquals(FeatureCatalog.all().size(), catalog.size());

        Map<String, Object> pro = catalog.stream()
                .filter(f -> FeatureCatalog.PLAN_PRO.equals(f.get("feature"))).findFirst().orElseThrow();
        assertEquals(false, pro.get("enabled"));
        assertEquals("none", pro.get("source"));
        assertEquals("Pro 订阅", pro.get("displayName"));
    }
}
