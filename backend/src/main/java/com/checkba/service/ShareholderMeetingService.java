package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ShareholderMeetingCheckRepository;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public static final String WORKPAPER_ROOT = "股东大会核查";
    public static final String FOLDER_NOTICE = "01-会议通知";
    public static final String FOLDER_RESOLUTION = "02-董事会决议";
    public static final String FOLDER_VOTE = "03-投票结果";
    public static final String FOLDER_WORKPAPER = "04-核查底稿";
    public static final String FOLDER_OPINION = "05-法律意见书";

    private final ShareholderMeetingCheckRepository checkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final CninfoAnnouncementService cninfoService;
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

    // ==================== 底稿夹 ====================

    /**
     * 幂等确保文件夹存在（同名已存在则直接返回）。
     */
    private ProjectFile ensureFolder(Long projectId, Long parentId, String name, Long userId) {
        Optional<ProjectFile> existing = projectFileRepository
                .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, name);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsFolder())) {
            return existing.get();
        }
        return projectFileService.createFolder(projectId, parentId, name, userId);
    }

    /**
     * 确保底稿夹五级子目录就绪，回写 workpaperFolderId。
     * 返回 slot 子文件夹名 → 文件夹 ID 的映射。
     */
    public Map<String, Long> ensureWorkpaperFolders(ShareholderMeetingCheck check, Long userId) {
        Long projectId = check.getProjectId();
        ProjectFile root = ensureFolder(projectId, null, WORKPAPER_ROOT, userId);
        String checkFolderName = sanitizeName(check.getCompanyName() + "_" + check.getMeetingName());
        ProjectFile checkFolder = ensureFolder(projectId, root.getId(), checkFolderName, userId);

        Map<String, Long> folders = new LinkedHashMap<>();
        for (String sub : List.of(FOLDER_NOTICE, FOLDER_RESOLUTION, FOLDER_VOTE, FOLDER_WORKPAPER, FOLDER_OPINION)) {
            folders.put(sub, ensureFolder(projectId, checkFolder.getId(), sub, userId).getId());
        }

        if (!checkFolder.getId().equals(check.getWorkpaperFolderId())) {
            check.setWorkpaperFolderId(checkFolder.getId());
            checkRepository.save(check);
        }
        return folders;
    }

    /** 文件夹/文件名清洗：剥掉路径分隔符等非法字符（validateNodeName 会拒绝） */
    private String sanitizeName(String name) {
        return name.replaceAll("[/\\\\]", "_").trim();
    }

    /**
     * 把字节流保存为项目文件（幂等：同名同目录则覆盖更新）。
     */
    private ProjectFile saveBytesAsProjectFile(Long projectId, Long parentId, String fileName,
                                               String fileType, byte[] bytes, Long userId) {
        ProjectFile file = projectFileService.createOrUpdateFile(
                projectId, parentId, sanitizeName(fileName), fileType, (long) bytes.length, null, null, userId);
        String savedPath = storageServiceFactory.getStorageService()
                .save(file.getFilePath(), new ByteArrayInputStream(bytes));
        file.setFilePath(savedPath);
        file.setFileSize((long) bytes.length);
        file.setUpdatedAt(LocalDateTime.now());
        return projectFileRepository.save(file);
    }

    // ==================== 巨潮拉取 ====================

    /**
     * 从巨潮拉取股东大会通知 + 董事会决议公告，PDF 落底稿夹并自动关联材料槽。
     * 不 fail-hard：部分成功也返回，errors 描述缺口。
     */
    public Map<String, Object> fetchFromCninfo(Long checkId, String market, Long userId) {
        ShareholderMeetingCheck check = get(checkId);
        if (!StringUtils.hasText(check.getStockCode())) {
            throw new IllegalArgumentException("请先填写股票代码");
        }
        if (check.getMeetingDate() == null) {
            throw new IllegalArgumentException("请先填写股东会召开日期");
        }
        String mkt = StringUtils.hasText(market) ? market : CninfoAnnouncementService.MARKET_SZ_SH_BJ;

        CninfoAnnouncementService.FetchResult fetched =
                cninfoService.fetchForMeeting(check.getStockCode(), mkt, check.getMeetingDate());

        Map<String, Long> folders = ensureWorkpaperFolders(check, userId);
        List<String> errors = new ArrayList<>(fetched.errors);
        Map<String, Object> result = new HashMap<>();

        if (fetched.notice != null) {
            result.put("notice", downloadAndAttach(check, fetched.notice,
                    folders.get(FOLDER_NOTICE), SLOT_NOTICE, userId, errors));
        }
        if (fetched.resolution != null) {
            result.put("resolution", downloadAndAttach(check, fetched.resolution,
                    folders.get(FOLDER_RESOLUTION), SLOT_RESOLUTION, userId, errors));
        }
        result.put("noticeCandidates", fetched.noticeCandidates.stream()
                .map(CninfoAnnouncementService.Announcement::title).toList());
        result.put("resolutionCandidates", fetched.resolutionCandidates.stream()
                .map(CninfoAnnouncementService.Announcement::title).toList());
        result.put("errors", errors);
        result.put("check", get(checkId));
        return result;
    }

    private Map<String, Object> downloadAndAttach(ShareholderMeetingCheck check,
                                                  CninfoAnnouncementService.Announcement ann,
                                                  Long folderId, String slot, Long userId,
                                                  List<String> errors) {
        Map<String, Object> info = new HashMap<>();
        info.put("title", ann.title());
        String pdfUrl = ann.pdfUrl();
        if (pdfUrl == null) {
            errors.add(ann.title() + "：附件地址为空，无法下载");
            return info;
        }
        try {
            byte[] bytes = cninfoService.downloadPdf(pdfUrl);
            String fileName = ann.title().endsWith(".pdf") || ann.title().endsWith(".PDF")
                    ? ann.title() : ann.title() + ".pdf";
            ProjectFile saved = saveBytesAsProjectFile(check.getProjectId(), folderId, fileName, "pdf", bytes, userId);
            attachMaterial(check.getId(), slot, saved.getId());
            info.put("fileId", saved.getId());
            info.put("fileName", saved.getName());
        } catch (Exception e) {
            log.warn("巨潮公告下载失败: {}", ann.title(), e);
            errors.add(ann.title() + "：下载失败 " + e.getMessage());
        }
        return info;
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
