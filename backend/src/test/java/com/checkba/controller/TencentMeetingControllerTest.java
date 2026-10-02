// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.model.entity.TencentMeetingSyncConfig;
import com.checkba.service.tmeet.TencentMeetingService;
import com.checkba.service.tmeet.dto.TmeetAuthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class TencentMeetingControllerTest {

    private TencentMeetingService tencentMeetingService;
    private TencentMeetingController controller;

    @BeforeEach
    void setUp() {
        tencentMeetingService = Mockito.mock(TencentMeetingService.class);
        controller = new TencentMeetingController(tencentMeetingService);
    }

    @Test
    void testGetAuthStatus() {
        TmeetAuthStatus status = TmeetAuthStatus.builder()
                .cliAvailable(true)
                .loggedIn(true)
                .userName("张律师")
                .openId("open_123")
                .build();
        when(tencentMeetingService.getAuthStatus()).thenReturn(status);

        Map<String, Object> resp = controller.getAuthStatus();
        assertEquals(0, resp.get("code"));
        assertEquals(status, resp.get("data"));
    }

    @Test
    void testConfigEndpoints() {
        TencentMeetingSyncConfig cfg = new TencentMeetingSyncConfig();
        cfg.setUserId(1L);
        cfg.setAutoSync(true);
        cfg.setSyncIntervalMinutes(30);

        when(tencentMeetingService.getOrCreateConfig(anyLong())).thenReturn(cfg);

        Map<String, Object> getResp = controller.getConfig(null);
        assertEquals(0, getResp.get("code"));
        assertEquals(cfg, getResp.get("data"));
    }

    @Test
    void testPrompts() {
        TencentMeetingRecord record = new TencentMeetingRecord();
        record.setId(10L);
        record.setSubject("案件研讨会");
        record.setMeetingCode("112233");

        when(tencentMeetingService.getMeeting(eq(10L), anyLong())).thenReturn(record);
        when(tencentMeetingService.buildMinutesKickoffPrompt(record)).thenReturn("腾讯会议纪要：请根据...");
        when(tencentMeetingService.buildTodosKickoffPrompt(record)).thenReturn("腾讯会议待办：请提取...");

        Map<String, Object> minResp = controller.minutesPrompt(null, 10L);
        assertEquals(0, minResp.get("code"));
        Map<?, ?> minData = (Map<?, ?>) minResp.get("data");
        assertEquals("腾讯会议纪要：请根据...", minData.get("prompt"));

        Map<String, Object> todoResp = controller.todosPrompt(null, 10L);
        assertEquals(0, todoResp.get("code"));
        Map<?, ?> todoData = (Map<?, ?>) todoResp.get("data");
        assertEquals("腾讯会议待办：请提取...", todoData.get("prompt"));
    }
}
