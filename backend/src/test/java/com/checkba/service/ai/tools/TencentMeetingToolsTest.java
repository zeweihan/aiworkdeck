// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.repository.TencentMeetingRecordRepository;
import com.checkba.service.tmeet.TencentMeetingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TencentMeetingToolsTest {

    @Mock
    private TencentMeetingRecordRepository recordRepository;

    @Mock
    private TencentMeetingService tencentMeetingService;

    @InjectMocks
    private TencentMeetingTools tools;

    private TencentMeetingRecord record1;
    private TencentMeetingRecord record2;

    @BeforeEach
    void setUp() {
        record1 = new TencentMeetingRecord();
        record1.setId(101L);
        record1.setSubject("股权激励签约决议会");
        record1.setMeetingCode("888-999-111");
        record1.setStartTime(LocalDateTime.of(2026, 10, 2, 14, 0));
        record1.setDuration("45分钟");
        record1.setStatus(TencentMeetingRecord.STATUS_SYNCED);
        record1.setProjectId(1L);
        record1.setSmartMinutesText("### 决议事项\n1. 签署持股平台协议");

        record2 = new TencentMeetingRecord();
        record2.setId(102L);
        record2.setSubject("法务周例会");
        record2.setMeetingCode("222-333-444");
        record2.setStartTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        record2.setDuration("30分钟");
        record2.setStatus(TencentMeetingRecord.STATUS_SYNCED);
    }

    @Test
    @DisplayName("tmeet_list_meetings: 无记录时返回友好提示")
    void testListMeetings_Empty() {
        when(recordRepository.findByProjectIdOrderByStartTimeDesc(1L)).thenReturn(Collections.emptyList());
        when(recordRepository.findAll()).thenReturn(Collections.emptyList());

        String result = tools.tmeet_list_meetings(1L, null);
        assertTrue(result.contains("暂无已同步的腾讯会议记录"));
    }

    @Test
    @DisplayName("tmeet_list_meetings: 正常列出项目会议及关键词过滤")
    void testListMeetings_WithFilter() {
        when(recordRepository.findByProjectIdOrderByStartTimeDesc(1L)).thenReturn(List.of(record1, record2));
        when(recordRepository.findByProjectIdAndSubjectContainingIgnoreCaseOrderByStartTimeDesc(1L, "股权"))
                .thenReturn(List.of(record1));

        String all = tools.tmeet_list_meetings(1L, null);
        assertTrue(all.contains("股权激励签约决议会"));
        assertTrue(all.contains("法务周例会"));
        assertTrue(all.contains("888-999-111"));

        String filtered = tools.tmeet_list_meetings(1L, "股权");
        assertTrue(filtered.contains("股权激励签约决议会"));
        assertFalse(filtered.contains("法务周例会"));
    }

    @Test
    @DisplayName("tmeet_get_transcript: 记录不存在时返回错误提示")
    void testGetTranscript_NotFound() {
        when(recordRepository.findById(999L)).thenReturn(Optional.empty());

        String result = tools.tmeet_get_transcript(1L, 999L);
        assertTrue(result.contains("未找到该腾讯会议记录"));
    }

    @Test
    @DisplayName("tmeet_get_transcript: 正常读取逐字稿与智能纪要素材")
    void testGetTranscript_Success() {
        when(recordRepository.findById(101L)).thenReturn(Optional.of(record1));
        when(tencentMeetingService.renderTranscriptText(record1))
                .thenReturn("[00:05] 张律师：请各位确认最终期权池比例。\n[00:15] 李总：我们同意按5%执行。");

        String result = tools.tmeet_get_transcript(1L, 101L);

        assertTrue(result.contains("股权激励签约决议会"));
        assertTrue(result.contains("888-999-111"));
        assertTrue(result.contains("决议事项"));
        assertTrue(result.contains("张律师：请各位确认最终期权池比例"));
        assertTrue(result.contains("李总：我们同意按5%执行"));
    }

    @Test
    @DisplayName("tmeet_get_transcript: 当会议未关联项目时自动关联当前项目")
    void testGetTranscript_AutoAssociateProject() {
        record2.setProjectId(null);
        when(recordRepository.findById(102L)).thenReturn(Optional.of(record2));
        when(tencentMeetingService.renderTranscriptText(record2)).thenReturn("");

        tools.tmeet_get_transcript(5L, 102L);

        verify(recordRepository).save(record2);
        org.junit.jupiter.api.Assertions.assertEquals(5L, record2.getProjectId());
    }
}
