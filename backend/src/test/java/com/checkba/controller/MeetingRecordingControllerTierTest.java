// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserSessionService;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.service.meeting.MeetingTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dev-board#968：会议列表带出生效转写档位。团队服务器上的非管理员读不到机器级的
 * /api/platform-services，前端付费转写确认靠这个字段知道「这里没有平台档、不会扣 Credits」。
 */
class MeetingRecordingControllerTierTest {

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(
                new LocalIdentityService(null, null, null, null, false));
    }

    @Test
    @DisplayName("会议列表返回生效档位 tier，项目成员即可读")
    void listCarriesEffectiveTier() {
        MeetingRecordingService meetingService = mock(MeetingRecordingService.class);
        MeetingTranscriptionService transcriptionService = mock(MeetingTranscriptionService.class);
        ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.resolveUserId("sess")).thenReturn(7L);
        AuthController.registerUserSessionService(userSessionService);
        when(projectMemberService.hasReadPermission(eq(1L), anyLong())).thenReturn(true);
        when(meetingService.list(1L)).thenReturn(List.of());
        when(transcriptionService.tierValue()).thenReturn("byok");

        Map<String, Object> res = new MeetingRecordingController(
                meetingService, transcriptionService, projectMemberService).list(1L, "sess");

        assertEquals("byok", res.get("tier"));
    }
}
