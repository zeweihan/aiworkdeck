// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.TencentMeetingRecord;
import com.checkba.model.entity.TencentMeetingSyncConfig;
import com.checkba.repository.TencentMeetingRecordRepository;
import com.checkba.repository.TencentMeetingSyncConfigRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.service.tmeet.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class TencentMeetingService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TmeetCliService tmeetCliService;
    private final TencentMeetingRecordRepository recordRepository;
    private final TencentMeetingSyncConfigRepository configRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TmeetAuthStatus getAuthStatus() {
        return tmeetCliService.getAuthStatus();
    }

    public Map<String, Object> startLogin() {
        return tmeetCliService.startLogin();
    }

    public boolean logout() {
        return tmeetCliService.logout();
    }

    @Transactional
    public TencentMeetingSyncConfig getOrCreateConfig(Long userId) {
        return configRepository.findByUserId(userId)
                .orElseGet(() -> {
                    TencentMeetingSyncConfig cfg = new TencentMeetingSyncConfig();
                    cfg.setUserId(userId);
                    cfg.setAutoSync(true);
                    cfg.setSyncIntervalMinutes(60);
                    cfg.setSyncDays(7);
                    return configRepository.save(cfg);
                });
    }

    @Transactional
    public TencentMeetingSyncConfig updateConfig(Long userId, TencentMeetingSyncConfig update) {
        TencentMeetingSyncConfig cfg = getOrCreateConfig(userId);
        if (update.getAutoSync() != null) cfg.setAutoSync(update.getAutoSync());
        if (update.getSyncIntervalMinutes() != null && update.getSyncIntervalMinutes() > 0) {
            cfg.setSyncIntervalMinutes(update.getSyncIntervalMinutes());
        }
        if (update.getSyncDays() != null && update.getSyncDays() > 0) {
            cfg.setSyncDays(update.getSyncDays());
        }
        if (update.getExcludeKeywords() != null) {
            cfg.setExcludeKeywords(update.getExcludeKeywords().trim());
        }
        return configRepository.save(cfg);
    }

    /**
     * 同步腾讯会议：拉取用户有权限的已结束会议与云录制，保存逐字稿与智能纪要。
     */
    @Transactional
    public SyncSummary syncMeetings(Long userId, Long projectId, boolean force) {
        TmeetAuthStatus auth = tmeetCliService.getAuthStatus();
        if (!auth.isLoggedIn()) {
            throw new IllegalStateException("腾讯会议未登录，请先在插件界面扫码登录");
        }

        TencentMeetingSyncConfig cfg = getOrCreateConfig(userId);
        cfg.setLastSyncStatus("RUNNING");
        cfg.setLastSyncMessage("正在拉取会议列表中...");
        configRepository.save(cfg);

        int added = 0;
        int skipped = 0;
        int totalFound = 0;

        try {
            int days = cfg.getSyncDays() != null && cfg.getSyncDays() > 0 ? cfg.getSyncDays() : 7;
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusDays(days);
            LocalDateTime until = now.plusHours(1);

            Set<String> excludeKeywords = parseKeywords(cfg.getExcludeKeywords());

            // 1. 合并「参加过的会议」与「自己的录制」
            Map<String, TmeetMeetingItem> candidateMeetings = new LinkedHashMap<>();

            try {
                List<TmeetMeetingItem> ended = tmeetCliService.listEndedMeetings(since, until, 50, null);
                for (TmeetMeetingItem m : ended) {
                    for (TmeetMeetingItem.TmeetRecordFile r : m.getRecords()) {
                        if (r.getRecordFileId() != null && !r.getRecordFileId().isBlank()) {
                            candidateMeetings.putIfAbsent(r.getRecordFileId(), m);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("拉取已结束会议列表告警: {}", e.getMessage());
            }

            try {
                List<TmeetMeetingItem> recorded = tmeetCliService.listRecordings(since, until, 50, null);
                for (TmeetMeetingItem m : recorded) {
                    for (TmeetMeetingItem.TmeetRecordFile r : m.getRecords()) {
                        if (r.getRecordFileId() != null && !r.getRecordFileId().isBlank()) {
                            candidateMeetings.putIfAbsent(r.getRecordFileId(), m);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("拉取录制列表告警: {}", e.getMessage());
            }

            totalFound = candidateMeetings.size();
            log.info("找到 {} 个腾讯会议录制项进行同步校验", totalFound);

            for (Map.Entry<String, TmeetMeetingItem> entry : candidateMeetings.entrySet()) {
                String recordFileId = entry.getKey();
                TmeetMeetingItem meeting = entry.getValue();

                // 检查排除关键词
                String subject = meeting.getSubject() != null ? meeting.getSubject() : "未命名会议";
                if (shouldExclude(subject, excludeKeywords)) {
                    skipped++;
                    continue;
                }

                // 查重：已同步且无需强制覆盖则跳过
                Optional<TencentMeetingRecord> existingOpt = recordRepository.findByRecordFileId(recordFileId);
                if (existingOpt.isPresent() && !force) {
                    TencentMeetingRecord existing = existingOpt.get();
                    // 如果传入了 projectId 且原有记录尚未绑定，顺便绑定
                    if (projectId != null && existing.getProjectId() == null) {
                        existing.setProjectId(projectId);
                        recordRepository.save(existing);
                    }
                    skipped++;
                    continue;
                }

                // 拉取该录制文件的逐字稿与智能纪要
                List<TmeetParagraph> paragraphs = Collections.emptyList();
                try {
                    paragraphs = tmeetCliService.getTranscript(meeting.getMeetingId(), recordFileId);
                } catch (Exception e) {
                    log.warn("拉取逐字稿失败 (meetingId={}, fid={}): {}", meeting.getMeetingId(), recordFileId, e.getMessage());
                }

                TmeetSmartMinutes smartMinutes = null;
                try {
                    smartMinutes = tmeetCliService.getSmartMinutes(recordFileId);
                } catch (Exception e) {
                    log.warn("拉取智能纪要失败 (fid={}): {}", recordFileId, e.getMessage());
                }

                Set<String> speakers = new LinkedHashSet<>();
                for (TmeetParagraph p : paragraphs) {
                    if (p.getSpeakerName() != null && !p.getSpeakerName().isBlank()) {
                        speakers.add(p.getSpeakerName());
                    }
                }

                // 找到对应的 recordFile 元数据
                TmeetMeetingItem.TmeetRecordFile matchedFile = meeting.getRecords().stream()
                        .filter(r -> recordFileId.equals(r.getRecordFileId()))
                        .findFirst()
                        .orElse(null);

                TencentMeetingRecord record = existingOpt.orElseGet(TencentMeetingRecord::new);
                record.setUserId(userId);
                if (projectId != null) {
                    record.setProjectId(projectId);
                }
                record.setMeetingId(meeting.getMeetingId());
                record.setMeetingCode(meeting.getMeetingCode());
                record.setRecordFileId(recordFileId);
                record.setSubject(subject);
                record.setMeetingType(meeting.getMeetingType());
                record.setSubMeetingId(meeting.getSubMeetingId());
                record.setStartTime(matchedFile != null && matchedFile.getMediaStartTime() != null
                        ? matchedFile.getMediaStartTime() : meeting.getStartTime());
                record.setEndTime(meeting.getEndTime());
                if (matchedFile != null && matchedFile.getDuration() != null) {
                    record.setDuration(matchedFile.getDuration());
                }
                record.setSpeakersJson(objectMapper.writeValueAsString(speakers));
                record.setTranscriptJson(objectMapper.writeValueAsString(paragraphs));
                if (smartMinutes != null) {
                    record.setSmartMinutesText(smartMinutes.getMinute());
                    record.setSmartMinutesJson(smartMinutes.getRawJson());
                }
                record.setStatus(TencentMeetingRecord.STATUS_SYNCED);
                record.setSyncedAt(LocalDateTime.now());
                record.setError(null);

                recordRepository.save(record);
                added++;
            }

            cfg.setLastSyncAt(LocalDateTime.now());
            cfg.setLastSyncStatus("SUCCESS");
            cfg.setLastSyncMessage(String.format("同步完成：扫描 %d 场，新增 %d 场，跳过 %d 场", totalFound, added, skipped));
            configRepository.save(cfg);

            return SyncSummary.builder()
                    .addedCount(added)
                    .skippedCount(skipped)
                    .totalFound(totalFound)
                    .message(cfg.getLastSyncMessage())
                    .build();

        } catch (Exception e) {
            log.error("腾讯会议同步发生异常", e);
            cfg.setLastSyncStatus("FAILED");
            cfg.setLastSyncMessage("同步失败: " + e.getMessage());
            configRepository.save(cfg);
            throw new RuntimeException("腾讯会议同步失败: " + e.getMessage(), e);
        }
    }

    public List<TencentMeetingRecord> listMeetings(Long userId, Long projectId, String keyword) {
        List<TencentMeetingRecord> list;
        if (keyword != null && !keyword.isBlank()) {
            list = recordRepository.findByUserIdAndSubjectContainingIgnoreCaseOrderByStartTimeDesc(userId, keyword.trim());
        } else {
            list = recordRepository.findByUserIdOrderByStartTimeDesc(userId);
        }
        return list;
    }

    public TencentMeetingRecord getMeeting(Long id, Long userId) {
        TencentMeetingRecord record = recordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会议记录不存在: id=" + id));
        if (!record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会议记录");
        }
        return record;
    }

    @Transactional
    public TencentMeetingRecord linkProject(Long id, Long projectId, Long userId) {
        TencentMeetingRecord record = getMeeting(id, userId);
        record.setProjectId(projectId);
        return recordRepository.save(record);
    }

    /**
     * 渲染纯文本逐字稿（格式：[01:23] 张三：内容）
     */
    public String renderTranscriptText(TencentMeetingRecord record) {
        if (record.getTranscriptJson() == null || record.getTranscriptJson().isBlank()) {
            return "";
        }
        try {
            List<TmeetParagraph> paragraphs = objectMapper.readValue(
                    record.getTranscriptJson(), new TypeReference<List<TmeetParagraph>>() {});
            StringBuilder sb = new StringBuilder();
            for (TmeetParagraph p : paragraphs) {
                sb.append("[").append(p.getStartTime()).append("] ")
                        .append(p.getSpeakerName()).append("：")
                        .append(p.getText()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("解析逐字稿 JSON 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 构造「生成会议纪要」的 kick-off prompt。
     * 开头必须以 skill 触发词「腾讯会议纪要」开头以命中注入。
     */
    public String buildMinutesKickoffPrompt(TencentMeetingRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("腾讯会议纪要：请根据腾讯会议「").append(record.getSubject()).append("」");
        if (record.getMeetingCode() != null && !record.getMeetingCode().isBlank()) {
            sb.append("（会议号：").append(record.getMeetingCode()).append("）");
        }
        sb.append("的转写记录（recordId=").append(record.getId()).append("），整理一份结构完备的专业会议纪要。\n\n");

        if (record.getStartTime() != null) {
            sb.append("- 开会时间：").append(record.getStartTime().format(TIME_FMT)).append("\n");
        }
        if (record.getDuration() != null) {
            sb.append("- 会议时长：").append(record.getDuration()).append("\n");
        }
        if (record.getSpeakersJson() != null) {
            try {
                List<String> sp = objectMapper.readValue(record.getSpeakersJson(), new TypeReference<>() {});
                if (!sp.isEmpty()) {
                    sb.append("- 与会发言人：").append(String.join("、", sp)).append("\n");
                }
            } catch (Exception ignored) {}
        }

        sb.append("\n请调用 tmeet_get_transcript 工具（recordId=").append(record.getId())
                .append("）读取全文及智能纪要素材，提炼以下四大模块：\n")
                .append("1. 会议基本信息与背景概览\n")
                .append("2. 核心议题讨论与各方关键陈述\n")
                .append("3. 明确达成的决议与共识事项\n")
                .append("4. 后续任务安排（Action Items，包括事项、责任人与时间点）");

        return sb.toString();
    }

    /**
     * 构造「准备 ToDo List」的 kick-off prompt。
     * 开头以触发词「腾讯会议待办」开头以命中注入。
     */
    public String buildTodosKickoffPrompt(TencentMeetingRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("腾讯会议待办：请提取腾讯会议「").append(record.getSubject()).append("」");
        if (record.getMeetingCode() != null && !record.getMeetingCode().isBlank()) {
            sb.append("（会议号：").append(record.getMeetingCode()).append("）");
        }
        sb.append("（recordId=").append(record.getId()).append("）中的所有行动项（Action Items / ToDo List）。\n\n");

        sb.append("请按以下要求处理：\n")
                .append("1. 调用 tmeet_get_transcript（recordId=").append(record.getId()).append("）读取会议全文；\n")
                .append("2. 提炼出明确的待办事项、负责人与预期完成时间；\n")
                .append("3. 为每一条重要待办事项调用系统工具 task_create，将其作为日程任务添加到当前项目中进行跟踪；\n")
                .append("4. 输出清晰易读的待办清单表格汇总。");

        return sb.toString();
    }

    /**
     * 将会议逐字稿与纪要导出为项目内的文档（Markdown 或 DOCX）。
     */
    @Transactional
    public ProjectFile exportToProjectDoc(Long recordId, Long projectId, Long userId) {
        TencentMeetingRecord record = getMeeting(recordId, userId);
        String transcript = renderTranscriptText(record);

        StringBuilder md = new StringBuilder();
        md.append("# ").append(record.getSubject()).append("\n\n");
        if (record.getStartTime() != null) {
            md.append("- **时间**：").append(record.getStartTime().format(TIME_FMT)).append("\n");
        }
        if (record.getMeetingCode() != null) {
            md.append("- **会议号**：").append(record.getMeetingCode()).append("\n");
        }
        if (record.getDuration() != null) {
            md.append("- **时长**：").append(record.getDuration()).append("\n");
        }
        if (record.getSpeakersJson() != null) {
            try {
                List<String> sp = objectMapper.readValue(record.getSpeakersJson(), new TypeReference<>() {});
                if (!sp.isEmpty()) {
                    md.append("- **发言人**：").append(String.join("、", sp)).append("\n");
                }
            } catch (Exception ignored) {}
        }
        md.append("- **来源**：腾讯会议 (tmeet)\n\n");

        if (record.getSmartMinutesText() != null && !record.getSmartMinutesText().isBlank()) {
            md.append("## 智能纪要\n\n").append(record.getSmartMinutesText()).append("\n\n");
        }

        md.append("## 逐字稿\n\n");
        if (!transcript.isEmpty()) {
            md.append(transcript).append("\n");
        } else {
            md.append("（暂无转写文本）\n");
        }

        byte[] bytes = md.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = "腾讯会议_" + sanitize(record.getSubject()) + ".md";

        ProjectFile file = projectFileService.createFile(projectId, null, fileName, "md",
                (long) bytes.length, null, null, userId, ProjectFileService.ConflictPolicy.RENAME);
        storageServiceFactory.getStorageService().save(file.getFilePath(), new ByteArrayInputStream(bytes));

        // 顺便把该会议关联到该项目
        record.setProjectId(projectId);
        recordRepository.save(record);

        return file;
    }

    private Set<String> parseKeywords(String kwStr) {
        if (kwStr == null || kwStr.isBlank()) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (String s : kwStr.split("[,，\\s]+")) {
            if (!s.isBlank()) set.add(s.trim().toLowerCase());
        }
        return set;
    }

    private boolean shouldExclude(String subject, Set<String> excludeKeywords) {
        if (excludeKeywords.isEmpty() || subject == null) return false;
        String s = subject.toLowerCase();
        for (String kw : excludeKeywords) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    private String sanitize(String name) {
        if (name == null) return "未命名";
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
    }

    @Data
    @Builder
    public static class SyncSummary {
        private int addedCount;
        private int skippedCount;
        private int totalFound;
        private String message;
    }
}
