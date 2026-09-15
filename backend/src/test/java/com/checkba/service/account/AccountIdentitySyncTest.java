// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.model.entity.User;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 官网身份 → 本机 User 行的唯一同步点（spec 2026-09-10 §5）。
 *
 * <p>三条不能退的线：
 * <ul>
 *   <li>**未连接账户 / 官网不可达：不动本机行，也不报错**——同步是顺手动作，
 *       为它把设置页或应用启动搞失败不划算；</li>
 *   <li>非 local-mode（团队案件库、插件云）整条短路：那里的账户是机器级状态，
 *       按它去改某一个租户的 User 行是张冠李戴；</li>
 *   <li>官网展示名为空时保留本机那份，头像则以官网为准（没传过就是没有）。</li>
 * </ul>
 */
class AccountIdentitySyncTest {

    private static final long LOCAL_USER = 42L;

    private AccountService accountService;
    private LocalIdentityService localIdentityService;
    private UserService userService;
    private AccountIdentitySync sync;
    private org.springframework.context.ApplicationEventPublisher events;
    private User localUser;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        localIdentityService = mock(LocalIdentityService.class);
        userService = mock(UserService.class);
        events = mock(org.springframework.context.ApplicationEventPublisher.class);
        sync = new AccountIdentitySync(accountService, localIdentityService, userService, events);

        localUser = new User();
        localUser.setId(LOCAL_USER);
        localUser.setUsername("hanzewei");
        localUser.setDisplayName("本机用户");

        lenient().when(localIdentityService.isLocalMode()).thenReturn(true);
        lenient().when(localIdentityService.localUserId()).thenReturn(LOCAL_USER);
        lenient().when(userService.getUserById(LOCAL_USER)).thenReturn(localUser);
        lenient().when(accountService.isConnected()).thenReturn(true);
    }

    private static Map<String, Object> view(String displayName, String avatarUrl) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("accountId", "acc_9f3a");
        v.put("displayName", displayName);
        v.put("avatarUrl", avatarUrl);
        v.put("displayNameIsDefault", false);
        return v;
    }

    @Test
    @DisplayName("已连接：官网的展示名与头像一起刷到本机行，视图原样返回")
    void refreshWritesBothFields() {
        when(accountService.profileIdentity())
                .thenReturn(view("韩泽伟", "https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=1"));

        Map<String, Object> got = sync.refresh();

        assertEquals("韩泽伟", got.get("displayName"));
        verify(userService).refreshDisplayNameFromWebsite(localUser, "韩泽伟");
        verify(userService).updateAvatar(LOCAL_USER, "https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=1");
    }

    @Test
    @DisplayName("官网没有头像：本机那份也清掉——官网是唯一权威源")
    void refreshClearsAvatarWhenTheWebsiteHasNone() {
        localUser.setAvatarUrl("http://127.0.0.1:5269/api/users/avatar/42_1.png");
        when(accountService.profileIdentity()).thenReturn(view("韩泽伟", null));

        sync.refresh();

        verify(userService).updateAvatar(LOCAL_USER, null);
    }

    @Test
    @DisplayName("头像没变：不白写一次库")
    void unchangedAvatarIsNotRewritten() {
        localUser.setAvatarUrl("https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=1");
        when(accountService.profileIdentity())
                .thenReturn(view("韩泽伟", "https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=1"));

        sync.refresh();

        verify(userService, never()).updateAvatar(anyLong(), any());
    }

    @Test
    @DisplayName("未连接账户：不出网、不动本机行、不报错")
    void notConnectedIsAQuietNoop() {
        when(accountService.isConnected()).thenReturn(false);

        assertNull(sync.refreshQuietly());

        verify(accountService, never()).profileIdentity();
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("官网不可达：静默版返回 null，本机行一个字不动")
    void unreachableWebsiteLeavesTheRowAlone() {
        when(accountService.profileIdentity()).thenThrow(
                new AccountException(AccountException.Kind.NETWORK, "无法连接 AI WorkDeck 服务器"));

        assertNull(sync.refreshQuietly());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("非 local-mode（团队案件库/插件云）：账户是机器级状态，绝不按它改某个租户的行")
    void serverModeNeverTouchesAnyRow() {
        when(localIdentityService.isLocalMode()).thenReturn(false);
        when(accountService.profileIdentity()).thenReturn(view("韩泽伟", null));

        assertNotNull(sync.refresh(), "视图照常返回，只是不落到本机行");
        assertNull(sync.refreshQuietly(), "status 那条路直接短路，连出站都不发");

        verifyNoInteractions(userService);
        verify(accountService, times(1)).profileIdentity();
    }

    @Test
    @DisplayName("改完昵称：只刷展示名，不碰头像")
    void applyDisplayNameOnlyTouchesTheName() {
        sync.applyDisplayName("韩律师");

        verify(userService).refreshDisplayNameFromWebsite(localUser, "韩律师");
        verify(userService, never()).updateAvatar(anyLong(), any());
    }

    @Test
    @DisplayName("同步到了展示名：发事件，让案件库连接跟着刷新（v0.38.2 走查）")
    void syncedDisplayNameIsPublishedForTheCaseLibrary() {
        when(accountService.profileIdentity()).thenReturn(view("韩律师", null));

        sync.refresh();

        verify(events).publishEvent(new AccountIdentitySync.DisplayNameSynced(LOCAL_USER, "韩律师"));
    }

    @Test
    @DisplayName("只动头像：不发展示名事件")
    void avatarOnlyWriteDoesNotPublish() {
        sync.applyAvatarUrl(null);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("传/删完头像：只刷头像，不碰展示名")
    void applyAvatarOnlyTouchesTheAvatar() {
        localUser.setAvatarUrl("https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=1");

        sync.applyAvatarUrl(null);

        verify(userService).updateAvatar(LOCAL_USER, null);
        verify(userService, never()).refreshDisplayNameFromWebsite(any(), any());
    }
}
