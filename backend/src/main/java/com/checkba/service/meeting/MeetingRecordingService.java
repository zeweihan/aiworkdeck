package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
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

    /**
     * 存放录音与转写稿的项目文件夹名。**两个名字都是「正名」**，不是新旧关系：
     * 建档时按界面语言取一个（{@link #folderName()}），查找时两个都认（{@link #ensureFolder}）。
     *
     * <p>为什么不像其他文案那样只留一个中文常量：这是**落到文件树上、用户看得见的目录**。
     * 只留中文，英文用户会被指到一个叫「会议录音」的目录；换成只留英文，存量中文安装里
     * 已有的那个目录会变成孤儿、下次录音另建一个。所以名字按语言选，查找跨语言兜住，
     * 一个项目里永远只有一个这样的目录。
     *
     * <p>常量本身必须是纯字面量：{@code static final} 上求值会在类加载期把语言冻死
     * （见 LangText 的类注释），所以取值走 {@link #folderName()} 方法。
     */
    public static final String FOLDER_NAME = "会议录音";
    public static final String FOLDER_NAME_EN = "Meeting Recordings";

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
        // 默认标题是**落库的用户数据**，按建档那一刻的界面语言定，事后切语言不改历史会议
        String title = LangText.of("会议 ", "Meeting ")
                + now.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));

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
                .orElseThrow(() -> new IllegalArgumentException(
                        LangText.of("会议不存在: ", "Meeting not found: ") + meetingId));
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
            throw new IllegalArgumentException(LangText.of(
                    "说话人名称格式非法", "Invalid speaker name format"));
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

    /**
     * 展示名解析：改过名用改的，否则「说话人N」/「Speaker N」。
     *
     * <p>这个默认名不落库（只有用户改过的名字才进 speakerNames），所以按当前界面语言取值是安全的：
     * 同一份转写稿在中文界面读作「说话人1」、英文界面读作「Speaker 1」，不会两种叫法混进同一份数据。
     * 面板侧的对应文案是 meeting.speakerDefaultName，两边措辞要一致。
     */
    public String displaySpeaker(Map<String, String> names, String speakerId) {
        String custom = names.get(speakerId);
        if (custom != null && !custom.isBlank()) return custom;
        return LangText.of("说话人" + speakerId, "Speaker " + speakerId);
    }

    /** 转写稿渲染成纯文本（AI 工具与导出共用）。 */
    public String renderTranscriptText(MeetingRecording meeting) {
        List<MeetingTranscriptParser.Segment> segments = readSegments(meeting);
        if (segments.isEmpty()) return "";
        Map<String, String> names = speakerDisplayNames(meeting);
        // 说话人与正文之间的分隔：中文用全角冒号，英文用半角加空格（全角冒号在英文行里很扎眼）
        String colon = LangText.of("：", ": ");
        StringBuilder sb = new StringBuilder();
        for (MeetingTranscriptParser.Segment s : segments) {
            sb.append('[').append(formatMs(s.start())).append("] ")
                    .append(displaySpeaker(names, s.speaker())).append(colon)
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

    /**
     * 导出结果：新文件 + 它所在文件夹的**实际**名字。
     *
     * <p>文件夹名要回给前端，是因为它按语言二选一、还可能是存量安装里早就建好的那一个
     * （见 {@link #FOLDER_NAME}）。界面上那句「见 X 文件夹」若照自己的语言硬拼，
     * 就会把用户指向一个不存在的目录名。
     */
    public record ExportResult(ProjectFile file, String folderName) {
    }

    /** 导出转写稿 docx 到录音文件夹，返回新文件与文件夹名。 */
    @Transactional
    public ExportResult exportTranscript(Long meetingId, Long userId) {
        MeetingRecording meeting = get(meetingId);
        String text = renderTranscriptText(meeting);
        if (text.isEmpty()) {
            throw new IllegalArgumentException(LangText.of(
                    "暂无转写内容可导出", "There is no transcript to export yet"));
        }
        byte[] docx = buildTranscriptDocx(meeting.getTitle(), text);
        ProjectFile folder = ensureFolder(meeting.getProjectId(), userId);
        String name = uniqueName(meeting.getProjectId(), folder.getId(),
                LangText.of("转写稿_", "Transcript_") + sanitize(meeting.getTitle()), ".docx");
        ProjectFile file = projectFileService.createFile(meeting.getProjectId(), folder.getId(),
                name, "docx", (long) docx.length, null, null, userId);
        storageServiceFactory.getStorageService().save(file.getFilePath(), new ByteArrayInputStream(docx));
        return new ExportResult(file, folder.getName());
    }

    /**
     * 「生成纪要」kick-off prompt。<b>开头必须是 skill 触发词</b>——SkillRouter 靠 prompt 文本命中
     * （pinnedSkillId 只裁工具不注入 prompt）。
     *
     * <p>因此这段话的语言不是「顺手也翻一下」：英文界面下必须以 skill.yml 的 <b>triggers_en</b>
     * 里的词开头（"meeting minutes"），否则英文用户点「生成会议纪要」拼出的 prompt 命不中本 skill，
     * 既拿不到 prompt.en.md 的指引、也拿不到 meeting_get_transcript / write_docx 白名单——
     * 按钮点了看着有反应，产出却不是纪要。改这里要同步 skill.yml 的 triggers_en。
     */
    public String buildMinutesKickoffPrompt(MeetingRecording meeting) {
        Map<String, String> names = speakerDisplayNames(meeting);
        boolean en = LangText.isEnglish();
        StringBuilder sb = new StringBuilder();
        sb.append(en ? "Meeting minutes generation task.\n\n" : "会议纪要生成任务。\n\n");
        sb.append(en ? "Meeting: " : "会议：").append(meeting.getTitle())
                .append(en ? " (meetingId=" : "（meetingId=").append(meeting.getId())
                .append(en ? ")\n" : "）\n");
        if (meeting.getDurationMs() != null) {
            sb.append(en ? "Duration: " : "时长：").append(formatMs(meeting.getDurationMs())).append('\n');
        }
        List<MeetingTranscriptParser.Segment> segments = readSegments(meeting);
        java.util.Set<String> speakerIds = new java.util.TreeSet<>();
        segments.forEach(s -> speakerIds.add(s.speaker()));
        if (!speakerIds.isEmpty()) {
            sb.append(en ? "Participants (from speaker separation): " : "与会人（说话人分离结果）：");
            sb.append(String.join(en ? ", " : "、",
                    speakerIds.stream().map(id -> displaySpeaker(names, id)).toList()));
            sb.append('\n');
        }
        if (en) {
            sb.append("\nFirst call the meeting_get_transcript tool (meetingId=").append(meeting.getId())
                    .append(") to read the full transcript and summary material, then produce the "
                            + "meeting minutes docx as the skill specifies.");
            return sb.toString();
        }
        sb.append("\n请先调用 meeting_get_transcript 工具（meetingId=").append(meeting.getId())
                .append("）读取完整转写稿与摘要素材，再按 skill 约定生成会议纪要 docx。");
        return sb.toString();
    }

    // ==================== 私有 ====================

    /** 建档那一刻的界面语言决定新文件夹叫什么；已存在的一律沿用，不改名。 */
    public static String folderName() {
        return LangText.of(FOLDER_NAME, FOLDER_NAME_EN);
    }

    /**
     * 找到（或建出）本项目的录音文件夹。
     *
     * <p><b>查找跨两个语言的名字</b>：先按当前语言找，再按另一个找。少了这一步，
     * 中文安装里已有的「会议录音」在切到英文后会被当成不存在，于是另建一个
     * 「Meeting Recordings」——同一个项目里两个同用途目录，旧录音留在旧目录里像丢了。
     */
    private ProjectFile ensureFolder(Long projectId, Long userId) {
        String preferred = folderName();
        String other = preferred.equals(FOLDER_NAME) ? FOLDER_NAME_EN : FOLDER_NAME;
        for (String candidate : List.of(preferred, other)) {
            Optional<ProjectFile> existing = projectFileRepository
                    .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, null, candidate);
            if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsFolder())) {
                return existing.get();
            }
        }
        return projectFileService.createFolder(projectId, null, preferred, userId);
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
            titleRun.setText(title + LangText.of(" 转写稿", " - Transcript"));
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
