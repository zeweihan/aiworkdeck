package com.checkba.controller;

import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队端点的本机透传层（dev-board#496）。照 membership 模板：requireUser → accountService
 * → 原样透传，不裁字段、不在桌面端抄一份角色判定。
 *
 * 本地这一层只该做三件事：参数校验（回业务信封而不是 4xx）、range 归一化、
 * 顺手缓存「共享项目名」。其余一律转发。
 */
class AccountControllerTeamTest {

    AccountController controller;
    AccountService accountService;
    TeamUsageSettings teamUsageSettings;
    TeamUsageUploadService teamUsageUploadService;
    TeamSettingsCache teamSettingsCache;

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        teamUsageSettings = mock(TeamUsageSettings.class);
        teamUsageUploadService = mock(TeamUsageUploadService.class);
        teamSettingsCache = mock(TeamSettingsCache.class);
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class),
                mock(MachineAccountGuard.class), mock(EntitlementService.class),
                teamUsageSettings, teamUsageUploadService, teamSettingsCache);
    }

    // ==================== 透传 ====================

    @Test
    @DisplayName("GET /team 原样透传官网响应，并顺手缓存「共享项目名」")
    void teamIsForwardedVerbatimAndCachesShareFlag() {
        Map<String, Object> remote = Map.of("team", Map.of("id", "t1", "shareProjectNames", true),
                "myRole", "OWNER", "members", List.of(), "pendingInvites", List.of());
        when(accountService.fetchTeam()).thenReturn(remote);

        Map<String, Object> envelope = controller.team(null);

        assertEquals(0, envelope.get("code"));
        assertSame(remote, envelope.get("data"), "不许裁字段：官网加字段桌面端要立刻能用");
        verify(teamSettingsCache).remember(any());
    }

    @Test
    @DisplayName("无团队（team:null）时清掉本机缓存的共享开关，不留上一个团队的设置")
    void noTeamClearsCachedShareFlag() {
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("team", null);
        remote.put("invites", List.of());
        when(accountService.fetchTeam()).thenReturn(remote);

        controller.team(null);

        verify(teamSettingsCache).clear();
        verify(teamSettingsCache, never()).remember(any());
    }

    @Test
    @DisplayName("summary 的 range 只收 7/30/90，其余归一到 7——非法值不该打到官网")
    void summaryRangeIsNormalized() {
        when(accountService.fetchTeamSummary(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Map.of("range", 7));

        controller.teamSummary(30, null);
        controller.teamSummary(999, null);
        controller.teamSummary(null, null);
        controller.teamSummary(-1, null);

        verify(accountService).fetchTeamSummary(30);
        verify(accountService, org.mockito.Mockito.times(3)).fetchTeamSummary(7);
    }

    // ==================== 参数校验：业务信封，绝不 4xx/4010 ====================

    @Test
    @DisplayName("空团队名 / 空手机号 / 空角色：拒绝，且服务层零触碰")
    void blankParametersAreRejectedBeforeAnyOutboundCall() {
        assertThrows(IllegalArgumentException.class, () -> controller.createTeam(Map.of("name", "  "), null));
        assertThrows(IllegalArgumentException.class, () -> controller.createTeam(null, null));
        assertThrows(IllegalArgumentException.class, () -> controller.createTeamInvite(Map.of("phone", ""), null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateTeamMember("acc-1", Map.of("role", ""), null));
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("PUT /team 没给任何可改字段时拒绝：整表回传会把没碰过的开关一起改掉")
    void emptyTeamPatchIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.updateTeam(Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateTeam(Map.of("name", "   "), null));
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("PUT /team 只转发实际给了的字段")
    void teamPatchOnlyForwardsProvidedFields() {
        when(accountService.updateTeam(any())).thenReturn(Map.of("team", Map.of("id", "t1")));

        controller.updateTeam(Map.of("shareProjectNames", true), null);

        verify(accountService).updateTeam(Map.of("shareProjectNames", true));
    }

    @Test
    @DisplayName("官网失败一律 code=1 业务信封，文案不含三个掉线子串")
    void accountFailuresStayBusinessErrors() {
        AccountException e = new AccountException(AccountException.Kind.NOT_CONNECTED,
                "尚未连接 AI WorkDeck 账户，可在设置页「账户与用量」粘贴账户 Key");
        Map<String, Object> envelope = controller.handleAccountException(e).getBody();

        assertEquals(1, envelope.get("code"), "团队端点的失败不是掉线，绝不能带 4010");
        String message = String.valueOf(envelope.get("message"));
        for (String needle : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(message.contains(needle), "文案含掉线子串「" + needle + "」：" + message);
        }
    }

    // ==================== 数据共享开关（纯本机） ====================

    @Test
    @DisplayName("usage-sharing 是本机状态，读写都不打官网")
    void usageSharingNeverTouchesTheWebsite() {
        when(teamUsageSettings.enabled()).thenReturn(false);
        when(teamUsageSettings.lastUploadAt()).thenReturn("");

        Map<String, Object> envelope = controller.teamUsageSharing(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertEquals(false, data.get("enabled"), "默认必须是关");
        assertEquals("", data.get("lastUploadAt"), "从未上报时给空串，不许编一个时间出来");
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("开关只收布尔值：缺字段或类型不对时拒绝，不静默当成 false")
    void usageSharingRequiresBoolean() {
        assertThrows(IllegalArgumentException.class, () -> controller.setTeamUsageSharing(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setTeamUsageSharing(Map.of("enabled", "true"), null));
        verify(teamUsageSettings, never()).setEnabled(org.mockito.ArgumentMatchers.anyBoolean());

        when(teamUsageSettings.enabled()).thenReturn(true);
        controller.setTeamUsageSharing(Map.of("enabled", true), null);
        verify(teamUsageSettings).setEnabled(true);
    }

    @Test
    @DisplayName("立即上报把跳过原因如实回传——用户主动点的按钮必须看得见为什么没传")
    void uploadNowReportsSkipReason() {
        when(teamUsageUploadService.uploadNow()).thenReturn(Map.of(
                "uploaded", 0, "skipped", true, "reason", "no_team", "lastUploadAt", ""));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.teamUsageUploadNow(null).get("data");

        assertEquals("no_team", data.get("reason"));
    }
}
