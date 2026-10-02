// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.TencentMeetingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TencentMeetingRecordRepository extends JpaRepository<TencentMeetingRecord, Long> {

    List<TencentMeetingRecord> findByUserIdOrderByStartTimeDesc(Long userId);

    List<TencentMeetingRecord> findByProjectIdOrderByStartTimeDesc(Long projectId);

    Optional<TencentMeetingRecord> findByRecordFileId(String recordFileId);

    Optional<TencentMeetingRecord> findByMeetingIdAndRecordFileId(String meetingId, String recordFileId);

    List<TencentMeetingRecord> findByUserIdAndSubjectContainingIgnoreCaseOrderByStartTimeDesc(Long userId, String keyword);

    List<TencentMeetingRecord> findByProjectIdAndSubjectContainingIgnoreCaseOrderByStartTimeDesc(Long projectId, String keyword);
}
