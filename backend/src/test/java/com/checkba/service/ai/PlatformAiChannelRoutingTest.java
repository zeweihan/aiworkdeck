package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 平台通道的取 key 路由。这条路由决定「花谁的额度」，四种形态各有硬约束：
 *
 * <ul>
 *   <li>local-mode：一字不动，永远走机器级密钥文件；</li>
 *   <li>server + 已桥接用户：走 per-user 密钥；</li>
 *   <li>server + 多租户实例上的未桥接用户 / 缺身份：<b>报错</b>，绝不回落机器级
 *       （回落等于拿别人的额度花钱）；</li>
 *   <li>server + 一个绑定都没有的团队服务器：机器级路径与改动前逐字一致。</li>
 * </ul>
 */
class PlatformAiChannelRoutingTest {

    @TempDir
    Path stateDir;

    private AccountService accountService;
    private PlatformAiKeyService perUser;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        perUser = mock(PlatformAiKeyService.class);
    }

    private PlatformAiChannel channel(boolean localMode) {
        return new PlatformAiChannel(accountService, perUser, localMode, stateDir.toString());
    }

    private void machineKeyAvailable() {
        when(accountService.isConnected()).thenReturn(true);
        when(accountService.fetchAiKey()).thenReturn(Map.of(
                "openrouterKey", "sk-or-machine", "limitUsd", 10.0));
    }

    private void bound(long userId, String key) {
        when(perUser.isBound(userId)).thenReturn(true);
        when(perUser.multiTenant()).thenReturn(true);
        when(perUser.resolve(userId)).thenReturn(Optional.of(
                new PlatformAiKeyService.Resolved(key, "fp-" + userId, 5.0)));
        when(perUser.fingerprintOrNull(userId)).thenReturn("fp-" + userId);
    }

    @Test
    @DisplayName("local-mode：即便库里有绑定也走机器级文件，行为一字不动")
    void localModeAlwaysUsesMachineKey() {
        machineKeyAvailable();
        bound(1L, "sk-or-alice");

        PlatformAiChannel channel = channel(true);
        assertEquals("sk-or-machine", PlatformAiUserScope.call(1L, channel::apiKey));
        verify(perUser, never()).resolve(any());
    }

    @Test
    @DisplayName("server + 已桥接：取本人的密钥")
    void boundUserGetsOwnKey() {
        machineKeyAvailable();
        bound(1L, "sk-or-alice");

        PlatformAiChannel channel = channel(false);
        assertEquals("sk-or-alice", PlatformAiUserScope.call(1L, channel::apiKey));
        assertEquals("fp-1", PlatformAiUserScope.call(1L, channel::keyFingerprint));
        assertTrue(channel.availableFor(1L));
        verify(accountService, never()).fetchAiKey();
    }

    @Test
    @DisplayName("多租户实例上的未桥接用户：明确拒绝，绝不回落机器级密钥")
    void unboundUserOnMultiTenantIsRefused() {
        machineKeyAvailable();
        when(perUser.multiTenant()).thenReturn(true);
        when(perUser.isBound(2L)).thenReturn(false);

        PlatformAiChannel channel = channel(false);
        AccountException e = assertThrows(AccountException.class,
                () -> PlatformAiUserScope.call(2L, channel::apiKey));
        assertNotMistakenForLogout(e.getMessage());
        assertFalse(channel.availableFor(2L));
        verify(accountService, never()).fetchAiKey();
    }

    @Test
    @DisplayName("多租户实例上缺身份：报错而不是记错账（漏传身份必须能被测试和冒烟抓到）")
    void missingScopeOnMultiTenantIsRefused() {
        machineKeyAvailable();
        when(perUser.multiTenant()).thenReturn(true);

        PlatformAiChannel channel = channel(false);
        AccountException e = assertThrows(AccountException.class, channel::apiKey);
        assertTrue(e.getMessage().contains("身份"), e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
        verify(accountService, never()).fetchAiKey();
    }

    @Test
    @DisplayName("已桥接但密钥未就绪（没分配额度/已过期）：给可操作的引导，不回落")
    void boundButNoKeyGivesActionableMessage() {
        machineKeyAvailable();
        when(perUser.isBound(1L)).thenReturn(true);
        when(perUser.multiTenant()).thenReturn(true);
        when(perUser.resolve(1L)).thenReturn(Optional.empty());

        PlatformAiChannel channel = channel(false);
        AccountException e = assertThrows(AccountException.class,
                () -> PlatformAiUserScope.call(1L, channel::apiKey));
        assertTrue(e.getMessage().contains("刷新额度"), e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
        verify(accountService, never()).fetchAiKey();
    }

    @Test
    @DisplayName("团队服务器（没有任何绑定）：机器级路径与改动前一致，缺身份也照常可用")
    void teamServerWithoutBindingsKeepsMachineBehaviour() {
        machineKeyAvailable();
        when(perUser.multiTenant()).thenReturn(false);

        PlatformAiChannel channel = channel(false);
        assertEquals("sk-or-machine", channel.apiKey());
        assertTrue(channel.availableFor(42L));
    }

    @Test
    @DisplayName("未连接账户的机器级路径：仍是原来那句「需要连接账户」")
    void machinePathWithoutAccountKeepsOriginalMessage() {
        when(accountService.isConnected()).thenReturn(false);
        when(perUser.multiTenant()).thenReturn(false);

        PlatformAiChannel channel = channel(false);
        AccountException e = assertThrows(AccountException.class, channel::apiKey);
        assertEquals(AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(e.getMessage().contains("连接账户"), e.getMessage());
    }

    @Test
    @DisplayName("密钥被拒：per-user 走删行，机器级走清缓存")
    void rejectionRoutesToTheRightStore() {
        bound(1L, "sk-or-alice");
        PlatformAiChannel channel = channel(false);

        channel.onKeyRejected(1L);
        verify(perUser).evict(1L);

        when(perUser.isBound(3L)).thenReturn(false);
        channel.onKeyRejected(3L);
        verify(perUser, never()).evict(3L);
    }

    private static void assertNotMistakenForLogout(String message) {
        assertNotNull(message);
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(message.contains(forbidden), "文案不得含「" + forbidden + "」: " + message);
        }
    }
}
