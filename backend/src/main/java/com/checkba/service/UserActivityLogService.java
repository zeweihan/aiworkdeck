package com.checkba.service;

import com.checkba.model.entity.UserActivityLog;
import com.checkba.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserActivityLogService {

    private final UserActivityLogRepository repository;

    public void logActivity(Long userId, String actionType, Long targetId, String targetName, Long duration, String metaInfo) {
        UserActivityLog log = new UserActivityLog();
        log.setUserId(userId);
        log.setActionType(actionType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);
        log.setDuration(duration);
        log.setMetaInfo(metaInfo);
        repository.save(log);
    }

    public List<UserActivityLog> getUserLogs(Long userId) {
        return repository.findTop500ByUserIdOrderByTimestampDesc(userId);
    }
}
