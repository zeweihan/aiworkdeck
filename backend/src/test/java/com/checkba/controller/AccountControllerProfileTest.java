// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountIdentitySync;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.team.TeamSettingsCache;
import com.checkba.service.team.TeamUsageSettings;
import com.checkba.service.team.TeamUsageUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 个人档案与头像的本机透传层（spec 2026-09-10 §5）。
 *
 * <p>四个端点一律「转发官网 + 把结果同步回本机 User 行」，本机不存第二份真相。
 * 动词上有一处刻意偏差：本机是 PUT，出站到官网是 PATCH（uni.request 没有 PATCH），
 * 与团队那组同源。
 */
class AccountControllerProfileTest {

    AccountController controller;
    AccountService accountService;
    AccountIdentitySync identitySync;

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        identitySync = mock(AccountIdentitySync.class);
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class),
                mock(MachineAccountGuard.class), mock(EntitlementService.class),
                mock(TeamUsageSettings.class), mock(TeamUsageUploadService.class),
                mock(TeamSettingsCache.class), identitySync);
    }

    private static Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("accountId", "acc_9f3a");
        v.put("displayName", "185****5325");
        v.put("avatarUrl", null);
        v.put("displayNameIsDefault", true);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        assertEquals(0, envelope.get("code"));
        return (Map<String, Object>) envelope.get("data");
    }

    @Test
    @DisplayName("GET /profile：回身份视图，顺手同步本机行；**不回 username**")
    void getProfileReturnsTheIdentityViewAndSyncs() {
        when(identitySync.refresh()).thenReturn(view());

        Map<String, Object> data = data(controller.profile(null));

        assertEquals("acc_9f3a", data.get("accountId"));
        assertEquals("185****5325", data.get("displayName"));
        assertTrue(data.containsKey("avatarUrl"));
        assertEquals(Boolean.TRUE, data.get("displayNameIsDefault"));
        assertFalse(data.containsKey("username"),
                "用户名退成内部标识，任何界面都不再当名字显示");
        verify(identitySync).refresh();
    }

    @Test
    @DisplayName("PUT /profile：出站改昵称，成功后把新名字同步到本机行")
    void putProfileForwardsAndSyncs() {
        when(accountService.updateDisplayName("韩泽伟"))
                .thenReturn(Map.of("displayName", "韩泽伟"));

        Map<String, Object> data = data(controller.updateProfile(Map.of("displayName", "韩泽伟"), null));

        assertEquals("韩泽伟", data.get("displayName"));
        verify(accountService).updateDisplayName("韩泽伟");
        verify(identitySync).applyDisplayName("韩泽伟");
    }

    @Test
    @DisplayName("PUT /profile 昵称为空：业务错误，一次出站都不发")
    void putProfileRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateProfile(Map.of("displayName", "   "), null));

        verify(accountService, never()).updateDisplayName(any());
        verifyNoInteractions(identitySync);
    }

    @Test
    @DisplayName("POST /avatar：multipart 原样转发，成功后把新头像地址同步到本机行")
    void postAvatarForwardsAndSyncs() throws Exception {
        Map<String, Object> uploaded = new LinkedHashMap<>();
        uploaded.put("avatarUpdatedAt", "2026-09-10T09:00:00.000Z");
        uploaded.put("avatarUrl", "https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=x");
        when(accountService.uploadAvatar(any(), eq("me.png"), eq("image/png"))).thenReturn(uploaded);
        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png",
                "PNGDATA".getBytes());

        Map<String, Object> data = data(controller.uploadAvatar(file, null));

        assertEquals("https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=x", data.get("avatarUrl"));
        verify(identitySync).applyAvatarUrl("https://www.aiworkdeck.com/api/avatar/acc_9f3a?v=x");
    }

    @Test
    @DisplayName("POST /avatar 空文件：业务错误，不把一个 0 字节的东西传上去")
    void postAvatarRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "me.png", "image/png", new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> controller.uploadAvatar(empty, null));

        verifyNoInteractions(identitySync);
    }

    @Test
    @DisplayName("DELETE /avatar：出站删除，本机行的头像跟着清空")
    void deleteAvatarForwardsAndSyncs() {
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("avatarUpdatedAt", null);
        deleted.put("avatarUrl", null);
        when(accountService.deleteAvatar()).thenReturn(deleted);

        Map<String, Object> data = data(controller.deleteAvatar(null));

        assertTrue(data.containsKey("avatarUrl"));
        assertNull(data.get("avatarUrl"));
        verify(identitySync).applyAvatarUrl(null);
    }

    @Test
    @DisplayName("GET /status：应用启动那一拉顺手把官网身份刷到本机行，官网不可达也不报错")
    void statusRefreshesIdentityQuietly() {
        when(accountService.status()).thenReturn(new LinkedHashMap<>(Map.of("connected", true)));
        when(identitySync.refreshQuietly()).thenReturn(null);

        Map<String, Object> data = data(controller.status(null));

        assertEquals(Boolean.TRUE, data.get("connected"));
        verify(identitySync).refreshQuietly();
    }
}
