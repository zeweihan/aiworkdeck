package com.checkba.service.team;

import com.checkba.service.LocalIdentityService;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队使用统计上报的四道闸与补传窗口（dev-board#496）。
 *
 * 每一道闸都对应一种「不该发但很容易发出去」的形态：开关没开、不是单机版、
 * 没连账户、不在任何团队里。任何一条漏了，都是在用户没同意的情况下把使用数据发出去。
 */
class TeamUsageUploadServiceTest {

    TeamUsageSettings settings;
    TeamUsageRollupService rollupService;
    AccountService accountService;
    LocalIdentityService localIdentity;
    TeamSettingsCache teamSettingsCache;
    TeamUsageUploadService service;

    /** settings 是有状态的（记哪些天传过了），用一个真集合替代打桩更贴近真实行为。 */
    Set<LocalDate> uploadedDates;

    @BeforeEach
    void setUp() {
        settings = mock(TeamUsageSettings.class);
        rollupService = mock(TeamUsageRollupService.class);
        accountService = mock(AccountService.class);
        localIdentity = mock(LocalIdentityService.class);
        teamSettingsCache = mock(TeamSettingsCache.class);
        uploadedDates = new HashSet<>();

        when(settings.enabled()).thenReturn(true);
        when(settings.lastUploadAt()).thenReturn("");
        when(settings.alreadyUploaded(any())).thenAnswer(i -> uploadedDates.contains(i.getArgument(0)));
        org.mockito.Mockito.doAnswer(i -> uploadedDates.add(i.getArgument(0)))
                .when(settings).markUploaded(any());

        when(localIdentity.isLocalMode()).thenReturn(true);
        when(localIdentity.localUserId()).thenReturn(7L);
        when(accountService.isConnected()).thenReturn(true);
        when(accountService.fetchTeam()).thenReturn(Map.of("team", Map.of("id", "t1", "name", "某某律所")));
        when(rollupService.rollupFor(any(), any())).thenAnswer(i -> busyDay(i.getArgument(0)));
        when(rollupService.toJson(any())).thenReturn("{}");

        service = new TeamUsageUploadService(settings, rollupService, accountService,
                localIdentity, teamSettingsCache);
    }

    // ==================== 四道闸 ====================

    @Test
    @DisplayName("开关关着：一个字节都不出本机，连团队都不去问")
    void disabledSwitchUploadsNothing() {
        when(settings.enabled()).thenReturn(false);

        Map<String, Object> result = service.uploadNow();

        assertEquals(true, result.get("skipped"));
        assertEquals("disabled", result.get("reason"));
        verifyNoInteractions(accountService, rollupService);
    }

    @Test
    @DisplayName("非 local-mode 不上报：server 模式下账户是机器级的，照发会把全服的活动记在管理员名下")
    void serverModeUploadsNothing() {
        when(localIdentity.isLocalMode()).thenReturn(false);

        Map<String, Object> result = service.uploadNow();

        assertEquals("not_local_mode", result.get("reason"));
        verifyNoInteractions(accountService, rollupService);
    }

    @Test
    @DisplayName("没连账户不上报：没有 awdk_ 就没有身份，官网也认不出这是谁的数据")
    void disconnectedAccountUploadsNothing() {
        when(accountService.isConnected()).thenReturn(false);

        Map<String, Object> result = service.uploadNow();

        assertEquals("not_connected", result.get("reason"));
        verify(accountService, never()).uploadTeamUsage(anyString());
    }

    @Test
    @DisplayName("不在任何团队里不上报（官网回 team:null）")
    void noTeamUploadsNothing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("team", null);
        body.put("invites", java.util.List.of());
        when(accountService.fetchTeam()).thenReturn(body);

        Map<String, Object> result = service.uploadNow();

        assertEquals("no_team", result.get("reason"));
        verify(accountService, never()).uploadTeamUsage(anyString());
    }

    // ==================== 补传窗口 ====================

    @Test
    @DisplayName("首轮补传最近 30 天，今天永远不传（当天还没过完，传的是半天数据）")
    void backfillsThirtyDaysAndNeverUploadsToday() {
        service.sync();

        verify(accountService, org.mockito.Mockito.times(TeamUsageUploadService.BACKFILL_DAYS))
                .uploadTeamUsage(anyString());
        verify(rollupService, never()).rollupFor(org.mockito.ArgumentMatchers.eq(LocalDate.now()), any());
        assertTrue(uploadedDates.contains(LocalDate.now().minusDays(1)));
        assertTrue(uploadedDates.contains(LocalDate.now().minusDays(TeamUsageUploadService.BACKFILL_DAYS)));
        assertFalse(uploadedDates.contains(LocalDate.now()));
    }

    @Test
    @DisplayName("已确认上报的日期不重传（第二轮零请求）")
    void alreadyUploadedDaysAreSkipped() {
        service.sync();
        org.mockito.Mockito.clearInvocations(accountService);

        service.sync();

        verify(accountService, never()).uploadTeamUsage(anyString());
    }

    @Test
    @DisplayName("整天没开过应用：不发请求，但也标记为已处理，免得每轮都重算一遍空数据")
    void emptyDaysAreMarkedWithoutUploading() {
        // 用 doAnswer 重新打桩：when(mock.call(...)) 会真的调一次已有的 answer（参数是 null），
        // 上面那条 answer 拿 null 去 toString 会 NPE——这是 Mockito 的经典坑
        org.mockito.Mockito.doAnswer(i -> emptyDay(i.getArgument(0)))
                .when(rollupService).rollupFor(any(), any());

        service.sync();

        verify(accountService, never()).uploadTeamUsage(anyString());
        assertTrue(uploadedDates.contains(LocalDate.now().minusDays(1)));
    }

    @Test
    @DisplayName("拉团队信息时顺手缓存「共享项目名」，供后台聚合决定带不带项目名")
    void teamSettingsAreCachedOnSync() {
        when(accountService.fetchTeam()).thenReturn(Map.of(
                "team", Map.of("id", "t1", "shareProjectNames", true)));

        service.sync();

        verify(teamSettingsCache).remember(any());
    }

    // ==================== helpers ====================

    private static Map<String, Object> busyDay(LocalDate date) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", date.toString());
        payload.put("appStarts", 1L);
        payload.put("activeMinutes", 120L);
        payload.put("aiTurns", 3L);
        payload.put("aiToolCalls", 0L);
        payload.put("agentEditActions", 0L);
        payload.put("manualEditActions", 0L);
        payload.put("projectsCreated", 0L);
        payload.put("projects", java.util.List.of());
        return payload;
    }

    private static Map<String, Object> emptyDay(LocalDate date) {
        Map<String, Object> payload = busyDay(date);
        for (String key : new String[]{"appStarts", "activeMinutes", "aiTurns", "aiToolCalls",
                "agentEditActions", "manualEditActions", "projectsCreated"}) {
            payload.put(key, 0L);
        }
        return payload;
    }
}
