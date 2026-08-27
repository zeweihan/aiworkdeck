package com.checkba.controller;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.service.meeting.MeetingTranscriptionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议录音：建档/结束/转写/说话人改名/导出/纪要 prompt。
 * 鉴权模式与 ShareholderMeetingController 一致（X-Session-Id → 项目成员校验）。
 */
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingRecordingController {

    private final MeetingRecordingService meetingService;
    private final MeetingTranscriptionService transcriptionService;
    private final ProjectMemberService projectMemberService;

    // 并发「开始录音」竞态防护：前端 isRecordingActive() 只是同一个 JS 运行时里的
    // 内存标记（utils/meetingRecorder.js 的模块级单例），管不到"同一账号在桌面端 +
    // 浏览器标签页各开一份""同一项目的多个成员各自点了开始"这类跨客户端场景——
    // 这两种都是正常使用即可触发，不需要恶意操作。MeetingRecordingService.create()
    // 经 ProjectFileService.createFile(RENAME) 建音频占位，RENAME 仍是"查名字是否
    // 被占→建档"两步式，中间有窗口：两个并发请求都查到"名字不存在"，各自建出
    // 一行同名 ProjectFile，物理路径只由 projectId/
    // parentId/name 决定（不含行 id），两行会落到同一个物理文件上，后续两段录音的
    // 分片上传各写各的 audioFileId 却写进同一个文件，互相覆盖。
    //
    // 锁必须包住对 meetingService.create() 的整次调用（含其 @Transactional 提交），
    // 而不能放进 create() 内部再对自身方法做同类里自调用——Spring 的事务代理只在
    // "经过代理的外部调用"上生效，同类自调用会绕开代理导致 @Transactional 整个失效。
    // 放在控制器这层、包住经代理的服务调用，两头都对。
    // 单机/单进程部署（当前架构）已经能完整堵住这条路径；水平扩到多实例需要数据库锁，
    // 不在当前架构范围内，届时再加。
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> startRecordingLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object startRecordingLock(Long projectId) {
        return startRecordingLocks.computeIfAbsent(projectId, k -> new Object());
    }

    private Long requireMemberByProject(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该资源", "You don't have permission to access this resource"));
        }
        return userId;
    }

    private Long requireMemberByMeeting(String sessionId, Long meetingId) {
        return requireMemberByProject(sessionId, meetingService.getProjectId(meetingId));
    }

    /** 开始录音：建会议 + 音频文件占位，前端拿 audioFileId 走分片上传。 */
    @PostMapping("/projects/{projectId}")
    public Map<String, Object> create(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByProject(sessionId, projectId);
        MeetingRecording meeting;
        synchronized (startRecordingLock(projectId)) {
            meeting = meetingService.create(projectId, userId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("meeting", meeting);
        result.put("configured", transcriptionService.isConfigured());
        return result;
    }

    @GetMapping("/projects/{projectId}")
    public Map<String, Object> list(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByProject(sessionId, projectId);
        List<MeetingRecording> meetings = meetingService.list(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("meetings", meetings);
        result.put("configured", transcriptionService.isConfigured());
        return result;
    }

    /** 详情（poll-on-read：转写中会顺手查一次听悟并推进状态）。 */
    @GetMapping("/{meetingId}")
    public MeetingRecording get(
            @PathVariable Long meetingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByMeeting(sessionId, meetingId);
        return transcriptionService.refreshIfNeeded(meetingService.get(meetingId));
    }

    /** 结束录音。durationMs 可空（崩溃恢复补刀）。凭证已配则自动提交转写。 */
    @PostMapping("/{meetingId}/finish")
    public MeetingRecording finish(
            @PathVariable Long meetingId,
            @RequestBody(required = false) FinishDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByMeeting(sessionId, meetingId);
        MeetingRecording meeting = meetingService.finish(meetingId, dto == null ? null : dto.getDurationMs());
        boolean autoTranscribe = dto == null || !Boolean.FALSE.equals(dto.getTranscribe());
        // 平台档欠着告知就不自动提交：**这一下才是录音真正离开本机的时刻**，
        // 而这条路上用户没有任何动作。不上传，会议留在「未转写」，
        // 面板上那块告知就摆在眼前，确认后点一下「开始转写」即可——
        // 录音本身完好，什么都没丢。
        if (autoTranscribe && transcriptionService.isConfigured()
                && !transcriptionService.recordingNoticePending()
                && MeetingRecording.STATUS_RECORDED.equals(meeting.getStatus())) {
            meeting = transcriptionService.startTranscription(meetingId);
        }
        return meeting;
    }

    /**
     * 资源管理器右键转写（dev-board#227）：把项目里已有的音频文件注册成会议记录并
     * 立即提交转写（与面板 finish 的自动提交同一套闸：凭证已配、平台档告知已确认）。
     * 幂等：同一文件重复注册返回既有记录；仅当记录尚可转写（RECORDED/FAILED/EMPTY）
     * 时才提交，TRANSCRIBED/TRANSCRIBING 的不重复扣费。
     */
    @PostMapping("/projects/{projectId}/register-file")
    public Map<String, Object> registerFile(
            @PathVariable Long projectId,
            @RequestBody RegisterFileDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByProject(sessionId, projectId);
        if (dto == null || dto.getFileId() == null) {
            throw new IllegalArgumentException(LangText.of("缺少文件标识", "Missing file id"));
        }
        MeetingRecording meeting = meetingService.registerExisting(projectId, dto.getFileId(), userId);
        boolean submitted = false;
        boolean transcribable = MeetingRecording.STATUS_RECORDED.equals(meeting.getStatus())
                || MeetingRecording.STATUS_FAILED.equals(meeting.getStatus())
                || MeetingRecording.STATUS_EMPTY.equals(meeting.getStatus());
        if (transcribable && transcriptionService.isConfigured()
                && !transcriptionService.recordingNoticePending()) {
            meeting = transcriptionService.startTranscription(meeting.getId());
            submitted = true;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("meeting", meeting);
        result.put("configured", transcriptionService.isConfigured());
        result.put("submitted", submitted);
        return result;
    }

    /** 手动（重新）提交转写。 */
    @PostMapping("/{meetingId}/transcribe")
    public MeetingRecording transcribe(
            @PathVariable Long meetingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByMeeting(sessionId, meetingId);
        return transcriptionService.startTranscription(meetingId);
    }

    /** 改标题 / 说话人改名（两者均可选）。 */
    @PatchMapping("/{meetingId}")
    public MeetingRecording update(
            @PathVariable Long meetingId,
            @RequestBody UpdateDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByMeeting(sessionId, meetingId);
        MeetingRecording meeting = meetingService.get(meetingId);
        if (dto.getTitle() != null) {
            meeting = meetingService.rename(meetingId, dto.getTitle());
        }
        if (dto.getSpeakerNames() != null) {
            meeting = meetingService.updateSpeakerNames(meetingId, dto.getSpeakerNames());
        }
        return meeting;
    }

    /**
     * 导出转写稿 docx 到项目的录音文件夹。
     *
     * <p>回 {@code {file, folderName}} 而不是裸 ProjectFile：文件夹名按语言二选一、也可能是
     * 存量安装里早就建好的那一个，界面上「见 X 文件夹」那句话只能用实际名字
     * （见 MeetingRecordingService.FOLDER_NAME 的注释）。
     */
    @PostMapping("/{meetingId}/export")
    public MeetingRecordingService.ExportResult export(
            @PathVariable Long meetingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByMeeting(sessionId, meetingId);
        return meetingService.exportTranscript(meetingId, userId);
    }

    /** 「生成纪要」kick-off prompt（前端经 sendExternalPrompt 交给 AI 面板）。 */
    @PostMapping("/{meetingId}/minutes-prompt")
    public Map<String, Object> minutesPrompt(
            @PathVariable Long meetingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberByMeeting(sessionId, meetingId);
        MeetingRecording meeting = meetingService.get(meetingId);
        if (!MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            throw new IllegalArgumentException(LangText.of("转写完成后才能生成纪要", "Minutes can only be generated after the transcription is finished"));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("prompt", meetingService.buildMinutesKickoffPrompt(meeting));
        return result;
    }

    @DeleteMapping("/{meetingId}")
    public void delete(
            @PathVariable Long meetingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMemberByMeeting(sessionId, meetingId);
        meetingService.delete(meetingId, userId);
    }

    // ==================== DTO ====================

    @Data
    public static class RegisterFileDto {
        private Long fileId;
    }

    @Data
    public static class FinishDto {
        private Long durationMs;
        /** 显式 false 时跳过自动转写（崩溃恢复补刀时用） */
        private Boolean transcribe;
    }

    @Data
    public static class UpdateDto {
        private String title;
        private Map<String, String> speakerNames;
    }
}
