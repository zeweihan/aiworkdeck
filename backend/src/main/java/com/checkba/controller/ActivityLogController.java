package com.checkba.controller;

import com.checkba.service.UserActivityLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityLogController {

    private final UserActivityLogService userActivityLogService;

    // X-Session-Id 一律 required=false：local-mode 免登请求不带 header，required
    // 会在进 controller 前被 Spring 以 MissingRequestHeaderException 500 掉；
    // 身份判定统一交给 getUserIdFromSession（server 模式 null header 仍按未登录拒绝）。
    @PostMapping("/log")
    public Map<String, Object> logActivity(
            @RequestBody LogRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }

        userActivityLogService.logActivity(
                userId,
                request.getActionType(),
                request.getTargetId(),
                request.getTargetName(),
                request.getDuration(),
                request.getMetaInfo()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "Logged");
        return result;
    }

    @GetMapping("/history")
    public Map<String, Object> getActivityHistory(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }

        var logs = userActivityLogService.getUserLogs(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", logs);
        return result;
    }

    static class LogRequest {
        private String actionType;
        private Long targetId;
        private String targetName;
        private Long duration;
        private String metaInfo;

        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }
        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }
        public String getTargetName() { return targetName; }
        public void setTargetName(String targetName) { this.targetName = targetName; }
        public Long getDuration() { return duration; }
        public void setDuration(Long duration) { this.duration = duration; }
        public String getMetaInfo() { return metaInfo; }
        public void setMetaInfo(String metaInfo) { this.metaInfo = metaInfo; }
    }
}
