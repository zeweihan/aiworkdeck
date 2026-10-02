// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.model.entity.TencentMeetingSyncConfig;
import com.checkba.service.tmeet.TencentMeetingService;
import com.checkba.service.tmeet.dto.TmeetAuthStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tmeet")
@RequiredArgsConstructor
@Slf4j
public class TencentMeetingController {

    private final TencentMeetingService tencentMeetingService;

    private Long resolveUserId(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        return userId != null ? userId : 1L; // 桌面单机版免登降级为用户 1
    }

    @GetMapping("/auth/status")
    public Map<String, Object> getAuthStatus() {
        TmeetAuthStatus status = tencentMeetingService.getAuthStatus();
        return Map.of("code", 0, "data", status);
    }

    @PostMapping("/auth/login-start")
    public Map<String, Object> startLogin() {
        Map<String, Object> res = tencentMeetingService.startLogin();
        return Map.of("code", 0, "data", res);
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout() {
        boolean ok = tencentMeetingService.logout();
        return Map.of("code", 0, "data", ok, "message", ok ? "已登出" : "登出失败");
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingSyncConfig config = tencentMeetingService.getOrCreateConfig(userId);
        return Map.of("code", 0, "data", config);
    }

    @PostMapping("/config")
    public Map<String, Object> updateConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody TencentMeetingSyncConfig update) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingSyncConfig config = tencentMeetingService.updateConfig(userId, update);
        return Map.of("code", 0, "data", config);
    }

    @PostMapping("/sync")
    public Map<String, Object> syncMeetings(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "false") boolean force) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingService.SyncSummary summary = tencentMeetingService.syncMeetings(userId, projectId, force);
        return Map.of("code", 0, "data", summary);
    }

    @GetMapping("/meetings")
    public Map<String, Object> listMeetings(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String keyword) {
        Long userId = resolveUserId(sessionId);
        List<TencentMeetingRecord> list = tencentMeetingService.listMeetings(userId, projectId, keyword);
        return Map.of("code", 0, "data", list);
    }

    @GetMapping("/meetings/{id}")
    public Map<String, Object> getMeeting(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingRecord record = tencentMeetingService.getMeeting(id, userId);
        return Map.of("code", 0, "data", record);
    }

    @PostMapping("/meetings/{id}/link-project")
    public Map<String, Object> linkProject(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id,
            @RequestBody LinkProjectDto body) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingRecord record = tencentMeetingService.linkProject(id, body.getProjectId(), userId);
        return Map.of("code", 0, "data", record);
    }

    @PostMapping("/meetings/{id}/minutes-prompt")
    public Map<String, Object> minutesPrompt(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingRecord record = tencentMeetingService.getMeeting(id, userId);
        String prompt = tencentMeetingService.buildMinutesKickoffPrompt(record);
        return Map.of("code", 0, "data", Map.of("prompt", prompt));
    }

    @PostMapping("/meetings/{id}/todos-prompt")
    public Map<String, Object> todosPrompt(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id) {
        Long userId = resolveUserId(sessionId);
        TencentMeetingRecord record = tencentMeetingService.getMeeting(id, userId);
        String prompt = tencentMeetingService.buildTodosKickoffPrompt(record);
        return Map.of("code", 0, "data", Map.of("prompt", prompt));
    }

    @PostMapping("/meetings/{id}/export-doc")
    public Map<String, Object> exportDoc(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id,
            @RequestBody ExportDocDto body) {
        Long userId = resolveUserId(sessionId);
        if (body.getProjectId() == null) {
            throw new IllegalArgumentException("必须指定目标项目 projectId");
        }
        ProjectFile file = tencentMeetingService.exportToProjectDoc(id, body.getProjectId(), userId);
        Map<String, Object> res = new HashMap<>();
        res.put("fileId", file.getId());
        res.put("fileName", file.getName());
        return Map.of("code", 0, "data", res);
    }

    @Data
    public static class LinkProjectDto {
        private Long projectId;
    }

    @Data
    public static class ExportDocDto {
        private Long projectId;
    }
}
