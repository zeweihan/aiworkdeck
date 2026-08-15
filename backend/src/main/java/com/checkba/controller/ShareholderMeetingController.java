package com.checkba.controller;

import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ShareholderMeetingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 股东大会核查：会话 CRUD 与材料关联。
 * 鉴权模式与 DdController 一致（X-Session-Id → 项目成员校验）。
 */
@RestController
@RequestMapping("/api/shareholder-meeting")
@RequiredArgsConstructor
public class ShareholderMeetingController {

    private final ShareholderMeetingService meetingService;
    private final ProjectMemberService projectMemberService;

    private Long requireMemberByProject(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该资源", "You don't have permission to access this resource"));
        }
        return userId;
    }

    private Long requireMemberByCheck(String sessionId, Long checkId) {
        return requireMemberByProject(sessionId, meetingService.getProjectIdByCheckId(checkId));
    }

    @GetMapping("/projects/{projectId}")
    public List<ShareholderMeetingCheck> list(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByProject(sessionId, projectId);
        return meetingService.list(projectId);
    }

    @PostMapping("/projects/{projectId}")
    public ShareholderMeetingCheck create(
            @PathVariable Long projectId,
            @RequestBody UpsertCheckDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByProject(sessionId, projectId);
        return meetingService.create(projectId, dto.getCompanyName(), dto.getStockCode(),
                dto.getMeetingName(), dto.getMeetingDate(), userId);
    }

    @GetMapping("/{checkId}")
    public ShareholderMeetingCheck get(
            @PathVariable Long checkId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        return meetingService.get(checkId);
    }

    @PutMapping("/{checkId}")
    public ShareholderMeetingCheck update(
            @PathVariable Long checkId,
            @RequestBody UpsertCheckDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        return meetingService.update(checkId, dto.getCompanyName(), dto.getStockCode(),
                dto.getMeetingName(), dto.getMeetingDate(), dto.getStatus());
    }

    @DeleteMapping("/{checkId}")
    public void delete(
            @PathVariable Long checkId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        meetingService.delete(checkId);
    }

    @PostMapping("/{checkId}/materials")
    public ShareholderMeetingCheck attachMaterial(
            @PathVariable Long checkId,
            @RequestBody MaterialDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        return meetingService.attachMaterial(checkId, dto.getSlot(), dto.getFileId());
    }

    @DeleteMapping("/{checkId}/materials")
    public ShareholderMeetingCheck detachMaterial(
            @PathVariable Long checkId,
            @RequestParam String slot,
            @RequestParam Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        return meetingService.detachMaterial(checkId, slot, fileId);
    }

    @PostMapping("/{checkId}/start")
    public java.util.Map<String, Object> start(
            @PathVariable Long checkId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByCheck(sessionId, checkId);
        return meetingService.start(checkId, userId);
    }

    @PostMapping("/{checkId}/fetch-cninfo")
    public java.util.Map<String, Object> fetchCninfo(
            @PathVariable Long checkId,
            @RequestParam(required = false) String market,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByCheck(sessionId, checkId);
        return meetingService.fetchFromCninfo(checkId, market, userId);
    }

    @PutMapping("/{checkId}/conversation")
    public ShareholderMeetingCheck bindConversation(
            @PathVariable Long checkId,
            @RequestBody ConversationDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByCheck(sessionId, checkId);
        return meetingService.bindConversation(checkId, dto.getConversationId(), dto.getStatus());
    }

    // ==================== DTO ====================

    @Data
    public static class UpsertCheckDto {
        private String companyName;
        private String stockCode;
        private String meetingName;
        private LocalDate meetingDate;
        private String status;
    }

    @Data
    public static class MaterialDto {
        private String slot;
        private Long fileId;
    }

    @Data
    public static class ConversationDto {
        private String conversationId;
        private String status;
    }
}
