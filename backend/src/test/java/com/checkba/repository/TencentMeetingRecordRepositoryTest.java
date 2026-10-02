// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.model.entity.TencentMeetingSyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tmeet-repo-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TencentMeetingRecordRepositoryTest {

    @Autowired
    private TencentMeetingRecordRepository recordRepository;

    @Autowired
    private TencentMeetingSyncConfigRepository configRepository;

    @Test
    void testSaveAndQueryRecord() {
        TencentMeetingRecord record = new TencentMeetingRecord();
        record.setUserId(100L);
        record.setProjectId(1L);
        record.setMeetingId("m-123456");
        record.setMeetingCode("987654321");
        record.setRecordFileId("rf-999");
        record.setSubject("每周例会与诉讼讨论");
        record.setMeetingType("普通会议");
        record.setStartTime(LocalDateTime.of(2026, 10, 2, 10, 0));
        record.setEndTime(LocalDateTime.of(2026, 10, 2, 11, 0));
        record.setDuration("01:00:00");
        record.setSpeakersJson("[\"张律师\",\"韩律师\"]");
        record.setTranscriptJson("[{\"startTime\":\"00:01\",\"speaker\":{\"user_name\":\"张律师\"},\"text\":\"开始开会\"}]");
        record.setSmartMinutesText("## 会议摘要\n讨论了本周诉讼进展。");
        record.setStatus(TencentMeetingRecord.STATUS_SYNCED);
        record.setSyncedAt(LocalDateTime.now());

        TencentMeetingRecord saved = recordRepository.saveAndFlush(record);
        assertNotNull(saved.getId());

        Optional<TencentMeetingRecord> byRecordFileId = recordRepository.findByRecordFileId("rf-999");
        assertTrue(byRecordFileId.isPresent());
        assertEquals("每周例会与诉讼讨论", byRecordFileId.get().getSubject());
        assertEquals("987654321", byRecordFileId.get().getMeetingCode());

        List<TencentMeetingRecord> userMeetings = recordRepository.findByUserIdOrderByStartTimeDesc(100L);
        assertEquals(1, userMeetings.size());

        List<TencentMeetingRecord> searchMeetings = recordRepository.findByUserIdAndSubjectContainingIgnoreCaseOrderByStartTimeDesc(100L, "诉讼");
        assertEquals(1, searchMeetings.size());

        List<TencentMeetingRecord> projectMeetings = recordRepository.findByProjectIdOrderByStartTimeDesc(1L);
        assertEquals(1, projectMeetings.size());
    }

    @Test
    void testSaveAndQuerySyncConfig() {
        TencentMeetingSyncConfig config = new TencentMeetingSyncConfig();
        config.setUserId(200L);
        config.setAutoSync(true);
        config.setSyncIntervalMinutes(30);
        config.setSyncDays(14);
        config.setExcludeKeywords("面试,背调");

        configRepository.saveAndFlush(config);

        Optional<TencentMeetingSyncConfig> found = configRepository.findByUserId(200L);
        assertTrue(found.isPresent());
        assertTrue(found.get().getAutoSync());
        assertEquals(30, found.get().getSyncIntervalMinutes());
        assertEquals(14, found.get().getSyncDays());
        assertEquals("面试,背调", found.get().getExcludeKeywords());
    }
}
