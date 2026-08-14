package com.checkba.repository;

import com.checkba.model.entity.MeetingRecording;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingRecordingRepository extends JpaRepository<MeetingRecording, Long> {

    List<MeetingRecording> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<MeetingRecording> findByProjectIdAndStatusOrderByCreatedAtDesc(Long projectId, String status);
}
