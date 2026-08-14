package com.checkba.service.ai.tools;

import com.checkba.model.entity.MeetingRecording;
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
            return "本项目还没有会议录音。请用户先在左栏「会议录音」面板录制。";
        }
        StringBuilder sb = new StringBuilder("本项目的会议录音：\n");
        for (MeetingRecording m : meetings) {
            sb.append("- meetingId=").append(m.getId())
                    .append(" | ").append(m.getTitle())
                    .append(" | 时长 ").append(m.getDurationMs() != null
                            ? MeetingRecordingService.formatMs(m.getDurationMs()) : "未知")
                    .append(" | 状态 ").append(statusLabel(m.getStatus()))
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
            return "该会议不属于当前项目。";
        }
        if (!MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            return "该会议尚未完成转写（当前状态：" + statusLabel(meeting.getStatus())
                    + "）。请用户在「会议录音」面板完成转写后再生成纪要。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("会议：").append(meeting.getTitle());
        if (meeting.getDurationMs() != null) {
            sb.append("（时长 ").append(MeetingRecordingService.formatMs(meeting.getDurationMs())).append("）");
        }
        sb.append("\n\n=== 转写稿（[时间] 说话人：内容）===\n");
        sb.append(meetingService.renderTranscriptText(meeting));
        appendSummary(sb, meeting);
        return sb.toString();
    }

    /** 听悟的章节/摘要/待办素材，有就带上——给纪要当参考，不作为最终结论。 */
    private void appendSummary(StringBuilder sb, MeetingRecording meeting) {
        if (meeting.getSummaryJson() == null || meeting.getSummaryJson().isBlank()) return;
        try {
            Map<?, ?> summary = objectMapper.readValue(meeting.getSummaryJson(), Map.class);
            sb.append("\n=== 机器摘要素材（仅供参考，以转写稿原文为准）===\n");
            Object chapters = summary.get("chapters");
            if (chapters instanceof List<?> list && !list.isEmpty()) {
                sb.append("章节速览：\n");
                for (Object c : list) {
                    if (c instanceof Map<?, ?> m) {
                        sb.append("- ").append(m.get("title")).append("：").append(m.get("summary")).append('\n');
                    }
                }
            }
            if (summary.get("summary") instanceof String s && !s.isBlank()) {
                sb.append("全文摘要：").append(s).append('\n');
            }
            if (summary.get("todos") instanceof List<?> todos && !todos.isEmpty()) {
                sb.append("待办线索：\n");
                todos.forEach(t -> sb.append("- ").append(t).append('\n'));
            }
            if (summary.get("keywords") instanceof List<?> kw && !kw.isEmpty()) {
                sb.append("关键词：").append(String.join("、", kw.stream().map(String::valueOf).toList())).append('\n');
            }
        } catch (Exception ignored) {
            // 摘要素材损坏不影响转写稿主体
        }
    }

    private String statusLabel(String status) {
        return switch (status) {
            case MeetingRecording.STATUS_RECORDING -> "录音中";
            case MeetingRecording.STATUS_RECORDED -> "已录音（未转写）";
            case MeetingRecording.STATUS_TRANSCRIBING -> "转写中";
            case MeetingRecording.STATUS_TRANSCRIBED -> "已转写";
            case MeetingRecording.STATUS_FAILED -> "转写失败";
            default -> status;
        };
    }
}
