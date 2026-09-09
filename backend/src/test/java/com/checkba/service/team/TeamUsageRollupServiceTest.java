// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.team;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.TelemetryEvent;
import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import com.checkba.version.WorkSession;
import com.checkba.version.WorkSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队使用统计日聚合（dev-board#496）。
 *
 * 四条硬口径：DRAFT 与 ACTIVE 段不算工时；单日 16 小时封顶；项目以不可逆短码出现，
 * payload 里不许出现项目 id 或项目名；「共享项目名」默认关，关着时 label 必须是 null。
 */
class TeamUsageRollupServiceTest {

    @TempDir
    Path stateDir;

    WorkSessionRepository workSessions;
    TelemetryEventRepository events;
    TokenUsageRepository tokenUsages;
    ProjectRepository projects;
    TeamSettingsCache teamSettings;
    InstallIdentityService identity;
    TeamUsageRollupService service;

    private static final Long USER = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @BeforeEach
    void setUp() {
        workSessions = mock(WorkSessionRepository.class);
        events = mock(TelemetryEventRepository.class);
        tokenUsages = mock(TokenUsageRepository.class);
        projects = mock(ProjectRepository.class);
        teamSettings = mock(TeamSettingsCache.class);
        identity = new InstallIdentityService(stateDir.toString());

        when(workSessions.findByUserIdAndStartedAtBetween(anyLong(), any(), any())).thenReturn(List.of());
        when(events.findByTsBetween(any(), any())).thenReturn(List.of());
        when(tokenUsages.findByCreatedAtAfter(any())).thenReturn(List.of());
        when(teamSettings.shareProjectNames()).thenReturn(false);

        service = new TeamUsageRollupService(workSessions, events, tokenUsages, projects,
                identity, teamSettings);
    }

    // ==================== 工作段口径 ====================

    @Test
    @DisplayName("DRAFT 段不算工时：另起一稿的长命分支可以挂几周，与人在不在电脑前无关")
    void draftSessionsAreExcluded() {
        givenSessions(
                session(1L, WorkSession.SessionType.DRAFT, WorkSession.Status.MERGED, 8 * 60),
                session(2L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 30));

        Map<String, Object> payload = service.rollupFor(DAY, USER);

        assertEquals(30L, payload.get("activeMinutes"), "DRAFT 的 8 小时不该进总数");
        assertEquals(1, projectRows(payload).size(), "DRAFT 的项目也不该出现在项目行里");
    }

    @Test
    @DisplayName("ACTIVE 段不算工时：还没结束的段算一次就变大一次，同一天反复聚合会越滚越大")
    void activeSessionsAreExcluded() {
        WorkSession running = session(3L, WorkSession.SessionType.WORK, WorkSession.Status.ACTIVE, 0);
        running.setEndedAt(null);
        givenSessions(running, session(4L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 45));

        Map<String, Object> payload = service.rollupFor(DAY, USER);

        assertEquals(45L, payload.get("activeMinutes"));
    }

    @Test
    @DisplayName("单日 16 小时封顶：总数与每一行项目都要封，只封总数的话单个项目仍能出现 40 小时")
    void dailyMinutesAreCappedAtSixteenHours() {
        givenSessions(
                session(5L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 20 * 60),
                session(6L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 20 * 60));

        Map<String, Object> payload = service.rollupFor(DAY, USER);

        assertEquals(16 * 60L, payload.get("activeMinutes"));
        for (Map<String, Object> row : projectRows(payload)) {
            assertTrue((Long) row.get("minutes") <= 16 * 60L,
                    "项目行也必须封顶，实际 " + row.get("minutes"));
        }
    }

    // ==================== 项目短码 ====================

    @Test
    @DisplayName("projectKey 稳定、16 位十六进制，且 payload 里不含原始项目 id 与项目名")
    void projectKeyIsStableAndDoesNotLeakTheRawId() {
        givenSessions(session(123456L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 60));
        when(projects.findById(123456L)).thenReturn(Optional.of(namedProject("融创集团破产重整")));

        Map<String, Object> first = service.rollupFor(DAY, USER);
        Map<String, Object> second = service.rollupFor(DAY, USER);

        String key = (String) projectRows(first).get(0).get("projectKey");
        assertNotNull(key);
        assertTrue(key.matches("[0-9a-f]{16}"), "短码应是 16 位十六进制，实际 " + key);
        assertEquals(key, projectRows(second).get(0).get("projectKey"), "同一项目两次聚合必须同码");

        String json = service.toJson(first);
        assertFalse(json.contains("123456"), "payload 不许出现原始项目 id：" + json);
        assertFalse(json.contains("融创"), "payload 不许出现项目名：" + json);
    }

    @Test
    @DisplayName("不同项目得到不同短码（否则团队面板会把两个案件并成一行）")
    void differentProjectsGetDifferentKeys() {
        givenSessions(
                session(11L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 10),
                session(22L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 20));

        List<Map<String, Object>> rows = projectRows(service.rollupFor(DAY, USER));

        assertEquals(2, rows.size());
        assertFalse(rows.get(0).get("projectKey").equals(rows.get(1).get("projectKey")));
    }

    @Test
    @DisplayName("共享项目名默认关：label 恒为 null，项目名一个字都不出本机")
    void projectNamesAreNotSharedByDefault() {
        givenSessions(session(9L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 10));
        when(projects.findById(9L)).thenReturn(Optional.of(namedProject("某某并购案")));

        assertNull(projectRows(service.rollupFor(DAY, USER)).get(0).get("label"));
    }

    @Test
    @DisplayName("团队显式打开共享项目名后才带 label（管理者的整体设置，不是每台机器各自决定）")
    void projectNamesAreSharedOnlyWhenTeamOptsIn() {
        when(teamSettings.shareProjectNames()).thenReturn(true);
        givenSessions(session(9L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 10));
        when(projects.findById(9L)).thenReturn(Optional.of(namedProject("某某并购案")));

        assertEquals("某某并购案", projectRows(service.rollupFor(DAY, USER)).get(0).get("label"));
    }

    // ==================== 事件计数与 token ====================

    @Test
    @DisplayName("六个计数的派生口径与 telemetry 日聚合一致（editor.action 按 attrs.agent 分两桶）")
    void countersMatchTelemetryDerivation() {
        when(events.findByTsBetween(any(), any())).thenReturn(List.of(
                event("app.start", null),
                event("ai.turn", null),
                event("ai.turn", null),
                event("ai.tool", null),
                event("editor.action", "{\"agent\":true}"),
                event("editor.action", "{\"agent\":false}"),
                event("editor.action", null),
                event("project.created", null),
                event("ui.nav", null)));

        Map<String, Object> payload = service.rollupFor(DAY, USER);

        assertEquals(1L, payload.get("appStarts"));
        assertEquals(2L, payload.get("aiTurns"));
        assertEquals(1L, payload.get("aiToolCalls"));
        assertEquals(1L, payload.get("agentEditActions"));
        assertEquals(2L, payload.get("manualEditActions"), "attrs 缺失也算人工编辑，不能算成 AI 的");
        assertEquals(1L, payload.get("projectsCreated"));
    }

    @Test
    @DisplayName("token 按 costSource 分桶，platform 与 estimate 两套口径不得合并")
    void tokensAreBucketedByCostSource() {
        when(tokenUsages.findByCreatedAtAfter(any())).thenReturn(List.of(
                tokenUsage(1000, "platform", 1L),
                tokenUsage(200, "estimate", 1L),
                tokenUsage(50, null, 1L)));

        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) service.rollupFor(DAY, USER).get("tokens");

        assertEquals(1000L, tokens.get("platform"));
        assertEquals(250L, tokens.get("estimate"), "costSource 缺失按 estimate 处理，不许并进 platform");
    }

    @Test
    @DisplayName("别的本机用户的 token 不计入：一台机器多个历史账号时不能互相串账")
    void otherUsersTokensAreNotCounted() {
        TokenUsage mine = tokenUsage(100, "platform", 1L);
        TokenUsage other = tokenUsage(900, "platform", 1L);
        other.setUserId(USER + 1);
        when(tokenUsages.findByCreatedAtAfter(any())).thenReturn(List.of(mine, other));

        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) service.rollupFor(DAY, USER).get("tokens");

        assertEquals(100L, tokens.get("platform"));
    }

    @Test
    @DisplayName("payload 只有计数、日期与短码：没有任何自由文本字段")
    void payloadCarriesNoFreeText() {
        givenSessions(session(42L, WorkSession.SessionType.WORK, WorkSession.Status.MERGED, 60));
        Map<String, Object> payload = service.rollupFor(DAY, USER);

        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if ("date".equals(e.getKey()) || "projects".equals(e.getKey()) || "tokens".equals(e.getKey())) continue;
            assertTrue(e.getValue() instanceof Number,
                    "顶层字段 " + e.getKey() + " 必须是计数，实际 " + e.getValue());
        }
        assertEquals(DAY.toString(), payload.get("date"));
    }

    // ==================== helpers ====================

    private void givenSessions(WorkSession... sessions) {
        when(workSessions.findByUserIdAndStartedAtBetween(anyLong(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(sessions)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectRows(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("projects");
    }

    private static WorkSession session(Long projectId, WorkSession.SessionType type,
                                       WorkSession.Status status, int minutes) {
        WorkSession s = new WorkSession();
        s.setProjectId(projectId);
        s.setUserId(USER);
        s.setSessionType(type);
        s.setStatus(status);
        LocalDateTime start = DAY.atTime(9, 0);
        s.setStartedAt(start);
        s.setEndedAt(start.plusMinutes(minutes));
        return s;
    }

    private static Project namedProject(String name) {
        Project p = new Project();
        p.setName(name);
        return p;
    }

    private static TelemetryEvent event(String name, String attrs) {
        TelemetryEvent e = new TelemetryEvent();
        e.setEventName(name);
        e.setAttrs(attrs);
        e.setTs(DAY.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant());
        return e;
    }

    private static TokenUsage tokenUsage(int total, String source, Long projectId) {
        TokenUsage u = new TokenUsage();
        u.setUserId(USER);
        u.setProjectId(projectId);
        u.setTotalTokens(total);
        u.setCostSource(source);
        u.setCreatedAt(DAY.atTime(11, 0));
        return u;
    }
}
