package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ShareholderMeetingCheckRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 股东大会核查会话管理：建档、材料关联。
 * 「开始核查」（底稿夹 + prompt 组装）见 start() 相关方法。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShareholderMeetingService {

    public static final String SLOT_NOTICE = "notice";
    public static final String SLOT_RESOLUTION = "resolution";
    public static final String SLOT_VOTE_RESULT = "voteResult";
    public static final String SLOT_TEMPLATE = "template";
    public static final String SLOT_OTHER = "other";

    private final ShareholderMeetingCheckRepository checkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ShareholderMeetingCheck> list(Long projectId) {
        return checkRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public ShareholderMeetingCheck get(Long checkId) {
        return checkRepository.findById(checkId)
                .orElseThrow(() -> new IllegalArgumentException("核查会话不存在: " + checkId));
    }

    public Long getProjectIdByCheckId(Long checkId) {
        return get(checkId).getProjectId();
    }

    public ShareholderMeetingCheck create(Long projectId, String companyName, String stockCode,
                                          String meetingName, LocalDate meetingDate, Long userId) {
        if (!StringUtils.hasText(companyName)) throw new IllegalArgumentException("公司名称不能为空");
        if (!StringUtils.hasText(meetingName)) throw new IllegalArgumentException("届次名称不能为空");
        ShareholderMeetingCheck check = new ShareholderMeetingCheck();
        check.setProjectId(projectId);
        check.setCompanyName(companyName.trim());
        check.setStockCode(StringUtils.hasText(stockCode) ? stockCode.trim() : null);
        check.setMeetingName(meetingName.trim());
        check.setMeetingDate(meetingDate);
        check.setCreatedBy(userId);
        return checkRepository.save(check);
    }

    public ShareholderMeetingCheck update(Long checkId, String companyName, String stockCode,
                                          String meetingName, LocalDate meetingDate, String status) {
        ShareholderMeetingCheck check = get(checkId);
        if (StringUtils.hasText(companyName)) check.setCompanyName(companyName.trim());
        if (stockCode != null) check.setStockCode(StringUtils.hasText(stockCode) ? stockCode.trim() : null);
        if (StringUtils.hasText(meetingName)) check.setMeetingName(meetingName.trim());
        if (meetingDate != null) check.setMeetingDate(meetingDate);
        if (StringUtils.hasText(status)) check.setStatus(status);
        return checkRepository.save(check);
    }

    public void delete(Long checkId) {
        checkRepository.deleteById(checkId);
    }

    public ShareholderMeetingCheck bindConversation(Long checkId, String conversationId, String status) {
        ShareholderMeetingCheck check = get(checkId);
        if (StringUtils.hasText(conversationId)) check.setConversationId(conversationId);
        if (StringUtils.hasText(status)) check.setStatus(status);
        return checkRepository.save(check);
    }

    /**
     * 关联材料：校验文件存在且属于同项目。notice/resolution/template 为单值槽（覆盖），
     * voteResult/other 为列表槽（追加去重）。
     */
    public ShareholderMeetingCheck attachMaterial(Long checkId, String slot, Long fileId) {
        ShareholderMeetingCheck check = get(checkId);
        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
        if (!check.getProjectId().equals(file.getProjectId())) {
            throw new IllegalArgumentException("文件不属于本项目");
        }
        if ("folder".equalsIgnoreCase(file.getFileType())) {
            throw new IllegalArgumentException("不能关联文件夹");
        }
        switch (slot) {
            case SLOT_NOTICE -> check.setNoticeFileId(fileId);
            case SLOT_RESOLUTION -> check.setResolutionFileId(fileId);
            case SLOT_TEMPLATE -> check.setTemplateFileId(fileId);
            case SLOT_VOTE_RESULT -> check.setVoteResultFileIds(appendId(check.getVoteResultFileIds(), fileId));
            case SLOT_OTHER -> check.setOtherFileIds(appendId(check.getOtherFileIds(), fileId));
            default -> throw new IllegalArgumentException("未知材料槽位: " + slot);
        }
        return checkRepository.save(check);
    }

    public ShareholderMeetingCheck detachMaterial(Long checkId, String slot, Long fileId) {
        ShareholderMeetingCheck check = get(checkId);
        switch (slot) {
            case SLOT_NOTICE -> check.setNoticeFileId(null);
            case SLOT_RESOLUTION -> check.setResolutionFileId(null);
            case SLOT_TEMPLATE -> check.setTemplateFileId(null);
            case SLOT_VOTE_RESULT -> check.setVoteResultFileIds(removeId(check.getVoteResultFileIds(), fileId));
            case SLOT_OTHER -> check.setOtherFileIds(removeId(check.getOtherFileIds(), fileId));
            default -> throw new IllegalArgumentException("未知材料槽位: " + slot);
        }
        return checkRepository.save(check);
    }

    // ==================== JSON id 列表 ====================

    public List<Long> parseIds(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            log.warn("材料 id 列表解析失败: {}", json, e);
            return new ArrayList<>();
        }
    }

    private String writeIds(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("材料 id 列表序列化失败", e);
        }
    }

    private String appendId(String json, Long fileId) {
        List<Long> ids = parseIds(json);
        if (!ids.contains(fileId)) ids.add(fileId);
        return writeIds(ids);
    }

    private String removeId(String json, Long fileId) {
        List<Long> ids = parseIds(json);
        ids.remove(fileId);
        return writeIds(ids);
    }
}
