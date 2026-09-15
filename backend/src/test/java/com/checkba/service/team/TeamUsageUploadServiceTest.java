// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    TeamProjectNameNotice projectNameNotice;
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
        projectNameNotice = mock(TeamProjectNameNotice.class);
        uploadedDates = new HashSet<>();
        // 默认「还没就项目名做过决定」——这是全新安装与刚加入团队时的真实形态
        when(teamSettingsCache.shareProjectNames()).thenReturn(true);

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
                localIdentity, teamSettingsCache, projectNameNotice);
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

    // ==================== 项目名确认闸（C4，v0.44.1 真机实测） ====================

    @Test
    @DisplayName("没就项目名做过决定时拦下上传：真实客户名不许在用户没看过告知之前进团队看板")
    void undecidedProjectNamesBlockUpload() {
        org.mockito.Mockito.doAnswer(i -> dayWithProject(i.getArgument(0), "某某公司破产清算"))
                .when(rollupService).rollupFor(any(), any());

        Map<String, Object> result = service.uploadNow();

        assertEquals(true, result.get("skipped"));
        assertEquals("project_names_pending", result.get("reason"));
        verify(accountService, never()).uploadTeamUsage(anyString());
        // 拦下的那天不能被记成已传，否则确认之后这段历史就永远缺一块
        assertFalse(uploadedDates.contains(LocalDate.now().minusDays(1)));
    }

    @Test
    @DisplayName("已同意：项目名照常随统计上传（默认 true 的裁决不变，只是先问一句）")
    void grantedNoticeUploadsProjectNames() {
        when(projectNameNotice.decided()).thenReturn(true);
        when(projectNameNotice.granted()).thenReturn(true);
        org.mockito.Mockito.doAnswer(i -> dayWithProject(i.getArgument(0), "某某公司破产清算"))
                .when(rollupService).rollupFor(any(), any());

        service.sync();

        verify(accountService, org.mockito.Mockito.atLeastOnce()).uploadTeamUsage(anyString());
        assertEquals("某某公司破产清算", firstUploadedLabel());
    }

    @Test
    @DisplayName("已拒绝：统计照常上报，但项目名被抹成 null（拦的是名字，不是整条通道）")
    void declinedNoticeUploadsWithoutProjectNames() {
        when(projectNameNotice.decided()).thenReturn(true);
        when(projectNameNotice.declined()).thenReturn(true);
        org.mockito.Mockito.doAnswer(i -> dayWithProject(i.getArgument(0), "某某公司破产清算"))
                .when(rollupService).rollupFor(any(), any());

        service.sync();

        verify(accountService, org.mockito.Mockito.times(TeamUsageUploadService.BACKFILL_DAYS))
                .uploadTeamUsage(anyString());
        assertEquals(null, firstUploadedLabel());
    }

    @Test
    @DisplayName("那天没有任何项目名时不拦：没有要确认的东西，统计照常走")
    void daysWithoutProjectNamesAreNeverBlocked() {
        org.mockito.Mockito.doAnswer(i -> dayWithProject(i.getArgument(0), null))
                .when(rollupService).rollupFor(any(), any());

        Map<String, Object> result = service.uploadNow();

        assertEquals(false, result.get("skipped"));
        verify(accountService, org.mockito.Mockito.atLeastOnce()).uploadTeamUsage(anyString());
    }

    @Test
    @DisplayName("待确认的项目名清单：按天去重，且一个网络请求都不发（这是给确认框用的预览）")
    void pendingProjectNamesArePreviewedWithoutNetwork() {
        org.mockito.Mockito.doAnswer(i -> dayWithProject(i.getArgument(0), "某某公司破产清算"))
                .when(rollupService).rollupFor(any(), any());

        java.util.List<String> names = service.pendingProjectNames();

        assertEquals(java.util.List.of("某某公司破产清算"), names);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("pending 标志：开关开着、团队允许项目名、本机还没决定过时为 true，决定过就落回 false")
    void pendingFlagTracksTheDecision() {
        assertTrue(service.projectNamesPending());

        when(projectNameNotice.decided()).thenReturn(true);
        assertFalse(service.projectNamesPending());
    }

    @Test
    @DisplayName("团队已关掉「共享项目名」时不问：本来就没有项目名会上传")
    void teamWithNamesOffNeverAsks() {
        when(teamSettingsCache.shareProjectNames()).thenReturn(false);

        assertFalse(service.projectNamesPending());
    }

    // ==================== helpers ====================

    /** 上报体里真正发出去的项目名（toJson 的入参就是即将序列化的 payload）。 */
    @SuppressWarnings("unchecked")
    private Object firstUploadedLabel() {
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(rollupService, org.mockito.Mockito.atLeastOnce()).toJson(captor.capture());
        Map<String, Object> payload = captor.getAllValues().get(0);
        java.util.List<Map<String, Object>> rows =
                (java.util.List<Map<String, Object>>) payload.get("projects");
        return rows.get(0).get("label");
    }

    /** 带一行项目的一天。{@code label} 为 null 表示团队关掉了共享项目名。 */
    private static Map<String, Object> dayWithProject(LocalDate date, String label) {
        Map<String, Object> payload = busyDay(date);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectKey", "9f2c1d4e5a6b7c8d");
        row.put("label", label);
        row.put("minutes", 95L);
        row.put("aiTurns", 3L);
        row.put("editActions", 0L);
        payload.put("projects", new java.util.ArrayList<>(java.util.List.of(row)));
        return payload;
    }

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
