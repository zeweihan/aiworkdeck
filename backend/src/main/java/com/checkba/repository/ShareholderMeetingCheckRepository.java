package com.checkba.repository;

import com.checkba.model.entity.ShareholderMeetingCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShareholderMeetingCheckRepository extends JpaRepository<ShareholderMeetingCheck, Long> {

    List<ShareholderMeetingCheck> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
