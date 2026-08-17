package com.checkba.service.ai.tools;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.service.LangText;
import com.checkba.service.meeting.MeetingRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会议录音工具：给 AI 编排读会议转写稿与摘要素材（生成纪要的 skill 用）。
 * projectId 由 ToolRegistry 从 ToolContext 强制注入，LLM 报的值不生效。
 *
 * <p>返回文本双语（LangText）：这些串是喂给模型的脚手架，英文会话里塞中文小标题
 * （「转写稿」「机器摘要素材」）等于要求模型跨语言理解自己的输入格式，也会漏进过程卡。
 * 转写内容本身是用户数据，一律原样不动。
 */
@Component
@RequiredArgsConstructor
public class MeetingTools implements AgentToolComponent {

    private final MeetingRecordingService meetingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ToolMeta(displayName = "列出会议录音", category = "meeting")
    @Tool("List meeting recordings of the current project with their id, title, duration and "
            + "transcription status. Call this first when the user refers to a meeting without an id.")
    public String meeting_list_recordings(@P("Project id (injected by runtime)") Long projectId) {
        List<MeetingRecording> meetings = meetingService.list(projectId);
        if (meetings.isEmpty()) {
            return LangText.of(
                    "本项目还没有会议录音。请用户先在左栏「会议录音」面板录制。",
                    "This project has no meeting recordings yet. Ask the user to record one in the "
                            + "Meeting Recording panel in the sidebar.");
        }
        StringBuilder sb = new StringBuilder(LangText.of(
                "本项目的会议录音：\n", "Meeting recordings in this project:\n"));
        for (MeetingRecording m : meetings) {
            sb.append("- meetingId=").append(m.getId())
                    .append(" | ").append(m.getTitle())
                    .append(LangText.of(" | 时长 ", " | duration ")).append(m.getDurationMs() != null
                            ? MeetingRecordingService.formatMs(m.getDurationMs())
                            : LangText.of("未知", "unknown"))
                    .append(LangText.of(" | 状态 ", " | status ")).append(statusLabel(m.getStatus()))
                    .append('\n');
        }
        return sb.toString();
    }

    @ToolMeta(displayName = "读取会议转写稿", category = "meeting")
    @Tool("Read the full diarized transcript of a meeting recording, plus auxiliary material "
            + "(chapters, summary, todos) when available. Returns plain text with speaker names "
            + "and timestamps. Use the meetingId from the kick-off prompt or meeting_list_recordings.")
    public String meeting_get_transcript(
            @P("Project id (injected by runtime)") Long projectId,
            @P("Meeting recording id") Long meetingId) {
        MeetingRecording meeting = meetingService.get(meetingId);
        if (!projectId.equals(meeting.getProjectId())) {
            return LangText.of("该会议不属于当前项目。",
                    "That meeting does not belong to the current project.");
        }
        if (!MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            return LangText.of(
                    "该会议尚未完成转写（当前状态：" + statusLabel(meeting.getStatus())
                            + "）。请用户在「会议录音」面板完成转写后再生成纪要。",
                    "That meeting has not finished transcription (current status: "
                            + statusLabel(meeting.getStatus()) + "). Ask the user to finish "
                            + "transcription in the Meeting Recording panel before generating minutes.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LangText.of("会议：", "Meeting: ")).append(meeting.getTitle());
        if (meeting.getDurationMs() != null) {
            sb.append(LangText.of("（时长 ", " (duration "))
                    .append(MeetingRecordingService.formatMs(meeting.getDurationMs()))
                    .append(LangText.of("）", ")"));
        }
        sb.append(LangText.of("\n\n=== 转写稿（[时间] 说话人：内容）===\n",
                "\n\n=== Transcript ([time] speaker: text) ===\n"));
        sb.append(meetingService.renderTranscriptText(meeting));
        appendSummary(sb, meeting);
        return sb.toString();
    }

    /** 听悟的章节/摘要/待办素材，有就带上——给纪要当参考，不作为最终结论。 */
    private void appendSummary(StringBuilder sb, MeetingRecording meeting) {
        if (meeting.getSummaryJson() == null || meeting.getSummaryJson().isBlank()) return;
        try {
            Map<?, ?> summary = objectMapper.readValue(meeting.getSummaryJson(), Map.class);
            sb.append(LangText.of("\n=== 机器摘要素材（仅供参考，以转写稿原文为准）===\n",
                    "\n=== Machine summary material (reference only; the transcript is authoritative) ===\n"));
            String colon = LangText.of("：", ": ");
            Object chapters = summary.get("chapters");
            if (chapters instanceof List<?> list && !list.isEmpty()) {
                sb.append(LangText.of("章节速览：\n", "Chapters:\n"));
                for (Object c : list) {
                    if (c instanceof Map<?, ?> m) {
                        sb.append("- ").append(m.get("title")).append(colon).append(m.get("summary")).append('\n');
                    }
                }
            }
            if (summary.get("summary") instanceof String s && !s.isBlank()) {
                sb.append(LangText.of("全文摘要：", "Overall summary: ")).append(s).append('\n');
            }
            if (summary.get("todos") instanceof List<?> todos && !todos.isEmpty()) {
                sb.append(LangText.of("待办线索：\n", "To-do leads:\n"));
                todos.forEach(t -> sb.append("- ").append(t).append('\n'));
            }
            if (summary.get("keywords") instanceof List<?> kw && !kw.isEmpty()) {
                sb.append(LangText.of("关键词：", "Keywords: "))
                        .append(String.join(LangText.of("、", ", "),
                                kw.stream().map(String::valueOf).toList()))
                        .append('\n');
            }
        } catch (Exception ignored) {
            // 摘要素材损坏不影响转写稿主体
        }
    }

    /** 与面板的 meeting.status* 同口径（那边是给人看的徽标，这里是给模型读的字面）。 */
    private String statusLabel(String status) {
        return switch (status) {
            case MeetingRecording.STATUS_RECORDING -> LangText.of("录音中", "recording");
            case MeetingRecording.STATUS_RECORDED -> LangText.of("已录音（未转写）", "recorded (not transcribed)");
            case MeetingRecording.STATUS_TRANSCRIBING -> LangText.of("转写中", "transcribing");
            case MeetingRecording.STATUS_TRANSCRIBED -> LangText.of("已转写", "transcribed");
            case MeetingRecording.STATUS_FAILED -> LangText.of("转写失败", "transcription failed");
            default -> status;
        };
    }
}
