// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.model.entity.TencentMeetingSyncConfig;
import com.checkba.repository.TencentMeetingRecordRepository;
import com.checkba.repository.TencentMeetingSyncConfigRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TencentMeetingServiceTest {

    private TmeetCliService tmeetCliService;
    private TencentMeetingRecordRepository recordRepository;
    private TencentMeetingSyncConfigRepository configRepository;
    private ProjectFileService projectFileService;
    private StorageServiceFactory storageServiceFactory;
    private TencentMeetingService service;

    @BeforeEach
    void setUp() {
        tmeetCliService = Mockito.mock(TmeetCliService.class);
        recordRepository = Mockito.mock(TencentMeetingRecordRepository.class);
        configRepository = Mockito.mock(TencentMeetingSyncConfigRepository.class);
        projectFileService = Mockito.mock(ProjectFileService.class);
        storageServiceFactory = Mockito.mock(StorageServiceFactory.class);

        service = new TencentMeetingService(
                tmeetCliService, recordRepository, configRepository,
                projectFileService, storageServiceFactory
        );
    }

    @Test
    void testGetOrCreateConfig_NewUser() {
        when(configRepository.findByUserId(101L)).thenReturn(Optional.empty());
        when(configRepository.save(any(TencentMeetingSyncConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TencentMeetingSyncConfig config = service.getOrCreateConfig(101L);
        assertNotNull(config);
        assertEquals(101L, config.getUserId());
        assertTrue(config.getAutoSync());
        assertEquals(60, config.getSyncIntervalMinutes());
        assertEquals(7, config.getSyncDays());
    }

    @Test
    void testRenderTranscriptText() {
        TencentMeetingRecord record = new TencentMeetingRecord();
        record.setTranscriptJson("[{\"startTime\":\"00:05\",\"speakerName\":\"张三\",\"text\":\"会议开始\"},"
                + "{\"startTime\":\"00:15\",\"speakerName\":\"李四\",\"text\":\"收到，讨论第一项\"}]");

        String text = service.renderTranscriptText(record);
        assertTrue(text.contains("[00:05] 张三：会议开始"));
        assertTrue(text.contains("[00:15] 李四：收到，讨论第一项"));
    }

    @Test
    void testKickoffPrompts() {
        TencentMeetingRecord record = new TencentMeetingRecord();
        record.setId(88L);
        record.setSubject("股权架构与投资人会议");
        record.setMeetingCode("123456789");
        record.setStartTime(LocalDateTime.of(2026, 10, 2, 14, 30));
        record.setDuration("45:00");
        record.setSpeakersJson("[\"张三\",\"李四\"]");

        String minutesPrompt = service.buildMinutesKickoffPrompt(record);
        assertTrue(minutesPrompt.startsWith("腾讯会议纪要："), "纪要 prompt 必须以触发词开头以命中 skill 注入");
        assertTrue(minutesPrompt.contains("股权架构与投资人会议"));
        assertTrue(minutesPrompt.contains("123456789"));
        assertTrue(minutesPrompt.contains("recordId=88"));
        assertTrue(minutesPrompt.contains("tmeet_get_transcript"));

        String todosPrompt = service.buildTodosKickoffPrompt(record);
        assertTrue(todosPrompt.startsWith("腾讯会议待办："), "待办 prompt 必须以触发词开头以命中 skill 注入");
        assertTrue(todosPrompt.contains("task_create"));
        assertTrue(todosPrompt.contains("recordId=88"));
    }
}
