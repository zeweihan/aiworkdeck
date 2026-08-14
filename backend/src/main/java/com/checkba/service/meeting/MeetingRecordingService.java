package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会议录音生命周期：建档（含音频文件占位）→ 结束 → 说话人改名 / 导出 / 删除，
 * 以及「生成纪要」的 kick-off prompt 组装。转写链路见 MeetingTranscriptionService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingRecordingService {

    public static final String FOLDER_NAME = "会议录音";

    private final MeetingRecordingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final com.checkba.storage.StorageServiceFactory storageServiceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 开始录音：建会议记录 + 在「会议录音」文件夹下建音频文件占位（前端拿 audioFileId 分片追加上传）。
     */
    @Transactional
    public MeetingRecording create(Long projectId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        String title = "会议 " + now.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));

        ProjectFile folder = ensureFolder(projectId, userId);
        String audioName = uniqueName(projectId, folder.getId(), title, ".webm");
        ProjectFile audio = projectFileService.createFile(
                projectId, folder.getId(), audioName, "webm", 0L, null, null, userId);

        MeetingRecording meeting = new MeetingRecording();
        meeting.setProjectId(projectId);
        meeting.setTitle(title);
        meeting.setStatus(MeetingRecording.STATUS_RECORDING);
        meeting.setAudioFileId(audio.getId());
        meeting.setCreatedBy(userId);
        return meetingRepository.save(meeting);
    }

    public List<MeetingRecording> list(Long projectId) {
        return meetingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public MeetingRecording get(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("会议不存在: " + meetingId));
    }

    public Long getProjectId(Long meetingId) {
        return get(meetingId).getProjectId();
    }

    /**
     * 结束录音。durationMs 可空（崩溃恢复路径：前端发现本地没有进行中的录音、
     * 服务端却停在 RECORDING 时补一刀 finish，此时时长未知）。幂等。
     */
    @Transactional
    public MeetingRecording finish(Long meetingId, Long durationMs) {
        MeetingRecording meeting = get(meetingId);
        if (MeetingRecording.STATUS_RECORDING.equals(meeting.getStatus())) {
            meeting.setStatus(MeetingRecording.STATUS_RECORDED);
        }
        if (durationMs != null && durationMs > 0) {
            meeting.setDurationMs(durationMs);
        }
        return meetingRepository.save(meeting);
    }

    @Transactional
    public MeetingRecording rename(Long meetingId, String title) {
        MeetingRecording meeting = get(meetingId);
        if (title != null && !title.isBlank()) {
            meeting.setTitle(title.trim());
        }
        return meetingRepository.save(meeting);
    }

    @Transactional
    public MeetingRecording updateSpeakerNames(Long meetingId, Map<String, String> names) {
        MeetingRecording meeting = get(meetingId);
        try {
            meeting.setSpeakerNames(names == null || names.isEmpty() ? null : objectMapper.writeValueAsString(names));
        } catch (Exception e) {
            throw new IllegalArgumentException("说话人名称格式非法");
        }
        return meetingRepository.save(meeting);
    }

    /** 删除会议记录与音频文件（音频删除失败只记日志，不挡记录删除）。 */
    @Transactional
    public void delete(Long meetingId, Long userId) {
        MeetingRecording meeting = get(meetingId);
        meetingRepository.delete(meeting);
        if (meeting.getAudioFileId() != null) {
            try {
                projectFileService.delete(meeting.getAudioFileId(), userId);
            } catch (Exception e) {
                log.warn("删除会议音频文件失败: fileId={}, {}", meeting.getAudioFileId(), e.toString());
            }
        }
    }

    /** 说话人编号 → 展示名（未改名的回落「说话人N」）。 */
    public Map<String, String> speakerDisplayNames(MeetingRecording meeting) {
        Map<String, String> names = new LinkedHashMap<>();
        if (meeting.getSpeakerNames() != null && !meeting.getSpeakerNames().isBlank()) {
            try {
                names.putAll(objectMapper.readValue(meeting.getSpeakerNames(),
                        new TypeReference<Map<String, String>>() {
                        }));
            } catch (Exception e) {
                log.warn("speakerNames 解析失败: meetingId={}", meeting.getId());
            }
        }
        return names;
    }

    /** 展示名解析：改过名用改的，否则「说话人N」。 */
    public String displaySpeaker(Map<String, String> names, String speakerId) {
        String custom = names.get(speakerId);
        return (custom == null || custom.isBlank()) ? "说话人" + speakerId : custom;
    }

    /** 转写稿渲染成纯文本（AI 工具与导出共用）。 */
    public String renderTranscriptText(MeetingRecording meeting) {
        List<MeetingTranscriptParser.Segment> segments = readSegments(meeting);
        if (segments.isEmpty()) return "";
        Map<String, String> names = speakerDisplayNames(meeting);
        StringBuilder sb = new StringBuilder();
        for (MeetingTranscriptParser.Segment s : segments) {
            sb.append('[').append(formatMs(s.start())).append("] ")
                    .append(displaySpeaker(names, s.speaker())).append('：')
                    .append(s.text()).append('\n');
        }
        return sb.toString();
    }

    public List<MeetingTranscriptParser.Segment> readSegments(MeetingRecording meeting) {
        if (meeting.getTranscriptJson() == null || meeting.getTranscriptJson().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(meeting.getTranscriptJson(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return raw.stream().map(m -> new MeetingTranscriptParser.Segment(
                    String.valueOf(m.getOrDefault("speaker", "1")),
                    ((Number) m.getOrDefault("start", 0)).longValue(),
                    ((Number) m.getOrDefault("end", 0)).longValue(),
                    String.valueOf(m.getOrDefault("text", "")))).toList();
        } catch (Exception e) {
            log.warn("transcriptJson 解析失败: meetingId={}", meeting.getId());
            return List.of();
        }
    }

    /** 导出转写稿 docx 到「会议录音」文件夹，返回新文件。 */
    @Transactional
    public ProjectFile exportTranscript(Long meetingId, Long userId) {
        MeetingRecording meeting = get(meetingId);
        String text = renderTranscriptText(meeting);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("暂无转写内容可导出");
        }
        byte[] docx = buildTranscriptDocx(meeting.getTitle(), text);
        ProjectFile folder = ensureFolder(meeting.getProjectId(), userId);
        String name = uniqueName(meeting.getProjectId(), folder.getId(),
                "转写稿_" + sanitize(meeting.getTitle()), ".docx");
        ProjectFile file = projectFileService.createFile(meeting.getProjectId(), folder.getId(),
                name, "docx", (long) docx.length, null, null, userId);
        storageServiceFactory.getStorageService().save(file.getFilePath(), new ByteArrayInputStream(docx));
        return file;
    }

    /**
     * 「生成纪要」kick-off prompt。开头必须是 skill 触发词「会议纪要」——
     * SkillRouter 靠 prompt 文本命中（pinnedSkillId 只裁工具不注入 prompt）。
     */
    public String buildMinutesKickoffPrompt(MeetingRecording meeting) {
        Map<String, String> names = speakerDisplayNames(meeting);
        StringBuilder sb = new StringBuilder();
        sb.append("会议纪要生成任务。\n\n");
        sb.append("会议：").append(meeting.getTitle()).append("（meetingId=").append(meeting.getId()).append("）\n");
        if (meeting.getDurationMs() != null) {
            sb.append("时长：").append(formatMs(meeting.getDurationMs())).append('\n');
        }
        List<MeetingTranscriptParser.Segment> segments = readSegments(meeting);
        java.util.Set<String> speakerIds = new java.util.TreeSet<>();
        segments.forEach(s -> speakerIds.add(s.speaker()));
        if (!speakerIds.isEmpty()) {
            sb.append("与会人（说话人分离结果）：");
            sb.append(String.join("、", speakerIds.stream().map(id -> displaySpeaker(names, id)).toList()));
            sb.append('\n');
        }
        sb.append("\n请先调用 meeting_get_transcript 工具（meetingId=").append(meeting.getId())
                .append("）读取完整转写稿与摘要素材，再按 skill 约定生成会议纪要 docx。");
        return sb.toString();
    }

    // ==================== 私有 ====================

    private ProjectFile ensureFolder(Long projectId, Long userId) {
        Optional<ProjectFile> existing = projectFileRepository
                .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, null, FOLDER_NAME);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsFolder())) {
            return existing.get();
        }
        return projectFileService.createFolder(projectId, null, FOLDER_NAME, userId);
    }

    /** createFile 对同名文件抛异常，这里先探测再加 (2)/(3) 后缀。 */
    private String uniqueName(Long projectId, Long parentId, String base, String ext) {
        String name = base + ext;
        int i = 2;
        while (projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(projectId, parentId, name, -1L)) {
            name = base + " (" + i + ")" + ext;
            i++;
        }
        return name;
    }

    private String sanitize(String name) {
        return name == null ? "" : name.replaceAll("[/\\\\]", "_").trim();
    }

    public static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private byte[] buildTranscriptDocx(String title, String text) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph titleP = doc.createParagraph();
            XWPFRun titleRun = titleP.createRun();
            titleRun.setText(title + " 转写稿");
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                XWPFParagraph p = doc.createParagraph();
                p.createRun().setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("转写稿导出失败: " + e.getMessage(), e);
        }
    }
}
