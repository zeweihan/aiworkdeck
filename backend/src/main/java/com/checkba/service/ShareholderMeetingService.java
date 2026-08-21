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
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("核查会话不存在: ", "Verification session not found: ") + checkId));
    }

    public Long getProjectIdByCheckId(Long checkId) {
        return get(checkId).getProjectId();
    }

    public ShareholderMeetingCheck create(Long projectId, String companyName, String stockCode,
                                          String meetingName, LocalDate meetingDate, Long userId) {
        if (!StringUtils.hasText(companyName)) throw new IllegalArgumentException(LangText.of("公司名称不能为空", "Company name must not be empty"));
        if (!StringUtils.hasText(meetingName)) throw new IllegalArgumentException(LangText.of("届次名称不能为空", "Meeting session name must not be empty"));
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
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("文件不存在: ", "File not found: ") + fileId));
        if (!check.getProjectId().equals(file.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于本项目", "File does not belong to this project"));
        }
        if ("folder".equalsIgnoreCase(file.getFileType())) {
            throw new IllegalArgumentException(LangText.of("不能关联文件夹", "A folder cannot be linked as material"));
        }
        switch (slot) {
            case SLOT_NOTICE -> check.setNoticeFileId(fileId);
            case SLOT_RESOLUTION -> check.setResolutionFileId(fileId);
            case SLOT_TEMPLATE -> check.setTemplateFileId(fileId);
            case SLOT_VOTE_RESULT -> check.setVoteResultFileIds(appendId(check.getVoteResultFileIds(), fileId));
            case SLOT_OTHER -> check.setOtherFileIds(appendId(check.getOtherFileIds(), fileId));
            default -> throw new IllegalArgumentException(LangText.of("未知材料槽位: ", "Unknown material slot: ") + slot);
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
            default -> throw new IllegalArgumentException(LangText.of("未知材料槽位: ", "Unknown material slot: ") + slot);
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
     *
     * createOrUpdateFile 自带 @Transactional，是独立于本类的一次提交；写字节失败时
     * 行已经落库，不补偿就会在文件树里留一条有名有大小、内容不存在的僵尸文件。
     * 只清理"这次新建的"行——如果 createOrUpdateFile 命中的是已有行（重复抓取覆盖
     * 更新），那条行在写字节失败前还指向一份能打开的旧内容，删掉反而比"元数据先一步
     * 被改成新值"更糟，所以只在确认是新建时才删除。
     */
    private ProjectFile saveBytesAsProjectFile(Long projectId, Long parentId, String fileName,
                                               String fileType, byte[] bytes, Long userId) {
        String cleanName = sanitizeName(fileName);
        boolean isNewFile = projectFileRepository
                .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, cleanName)
                .isEmpty();
        ProjectFile file = projectFileService.createOrUpdateFile(
                projectId, parentId, cleanName, fileType, (long) bytes.length, null, null, userId);
        try {
            String savedPath = storageServiceFactory.getStorageService()
                    .save(file.getFilePath(), new ByteArrayInputStream(bytes));
            file.setFilePath(savedPath);
            file.setFileSize((long) bytes.length);
            file.setUpdatedAt(LocalDateTime.now());
            return projectFileRepository.save(file);
        } catch (RuntimeException e) {
            if (isNewFile) {
                try {
                    projectFileRepository.deleteById(file.getId());
                } catch (Exception cleanupEx) {
                    log.warn("写盘失败后清理孤儿文件行也失败: fileId={}", file.getId(), cleanupEx);
                }
            }
            throw e;
        }
    }

    // ==================== 巨潮拉取 ====================

    /**
     * 从巨潮拉取股东大会通知 + 董事会决议公告，PDF 落底稿夹并自动关联材料槽。
     * 不 fail-hard：部分成功也返回，errors 描述缺口。
     */
    public Map<String, Object> fetchFromCninfo(Long checkId, String market, Long userId) {
        ShareholderMeetingCheck check = get(checkId);
        if (!StringUtils.hasText(check.getStockCode())) {
            throw new IllegalArgumentException(LangText.of("请先填写股票代码", "Please fill in the stock code first"));
        }
        if (check.getMeetingDate() == null) {
            throw new IllegalArgumentException(LangText.of("请先填写股东会召开日期", "Please fill in the meeting date first"));
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
            errors.add(ann.title() + LangText.of("：附件地址为空，无法下载", ": attachment URL is empty, cannot download"));
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
            errors.add(ann.title() + LangText.of("：下载失败 ", ": download failed ") + e.getMessage());
        }
        return info;
    }

    // ==================== 开始核查 ====================

    /**
     * 「开始核查」：底稿夹就绪、材料复制进对应子目录（自包含）、组装 kick-off prompt。
     * prompt 由前端交给 AI 聊天面板以 AGENT 模式发送（触发词命中 skill 注入）。
     */
    public Map<String, Object> start(Long checkId, Long userId) {
        ShareholderMeetingCheck check = get(checkId);
        Map<String, Long> folders = ensureWorkpaperFolders(check, userId);

        Map<String, List<ProjectFile>> materials = new LinkedHashMap<>();
        materials.put("股东大会通知", copyIntoFolder(check, singletonList(check.getNoticeFileId()),
                folders.get(FOLDER_NOTICE), userId));
        materials.put("董事会决议公告", copyIntoFolder(check, singletonList(check.getResolutionFileId()),
                folders.get(FOLDER_RESOLUTION), userId));
        materials.put("投票结果", copyIntoFolder(check, parseIds(check.getVoteResultFileIds()),
                folders.get(FOLDER_VOTE), userId));
        // 模板/初稿与其他材料不强制归入底稿夹子目录，原位引用
        materials.put("意见书模板或会前初稿", findFiles(singletonList(check.getTemplateFileId())));
        materials.put("其他材料", findFiles(parseIds(check.getOtherFileIds())));

        String prompt = buildKickoffPrompt(
                check.getCompanyName(), check.getStockCode(), check.getMeetingName(), check.getMeetingDate(),
                materials, folders.get(FOLDER_WORKPAPER), folders.get(FOLDER_OPINION));

        check = get(checkId);
        check.setStatus("READY");
        checkRepository.save(check);

        Map<String, Object> result = new HashMap<>();
        result.put("prompt", prompt);
        result.put("workpaperFolderId", check.getWorkpaperFolderId());
        result.put("check", check);
        return result;
    }

    private List<Long> singletonList(Long id) {
        return id == null ? List.of() : List.of(id);
    }

    /**
     * 按 id 取材料文件，**排除回收站里的**。
     *
     * <p>裸 findById 不过滤 isDeleted，而 ProjectFileService 完全不知道股东大会核查这回事、
     * 文件被删时从不解绑材料槽。于是材料被丢进回收站之后，kick-off prompt 仍把它列成
     * 一份在场的材料、也不进「缺失材料」告警——与这段代码为 null 槽位精心实现的
     * 缺失提示自相矛盾；若文件已被彻底删除，后续复制或 AI 读取还会失败，
     * 而清单从没提醒过它没了。
     */
    List<ProjectFile> findFiles(List<Long> ids) {
        List<ProjectFile> files = new ArrayList<>();
        for (Long id : ids) {
            projectFileRepository.findById(id)
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                    .ifPresent(files::add);
        }
        return files;
    }

    /**
     * 把材料复制进底稿夹子目录（已在目录内的跳过），返回底稿夹内的有效文件列表。
     */
    private List<ProjectFile> copyIntoFolder(ShareholderMeetingCheck check, List<Long> fileIds,
                                             Long targetFolderId, Long userId) {
        List<ProjectFile> effective = new ArrayList<>();
        List<Long> toCopy = new ArrayList<>();
        for (ProjectFile f : findFiles(fileIds)) {
            if (targetFolderId.equals(f.getParentId())) {
                effective.add(f);
            } else {
                // 目标目录下已有同名文件（往期已复制过）则直接复用，避免重复副本
                Optional<ProjectFile> existing = projectFileRepository
                        .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
                                check.getProjectId(), targetFolderId, f.getName());
                if (existing.isPresent()) {
                    effective.add(existing.get());
                } else {
                    toCopy.add(f.getId());
                }
            }
        }
        if (!toCopy.isEmpty()) {
            com.checkba.model.dto.ProjectFileBatchRequest req = new com.checkba.model.dto.ProjectFileBatchRequest();
            req.setFileIds(toCopy);
            req.setTargetParentId(targetFolderId);
            effective.addAll(projectFileService.batchCopy(check.getProjectId(), req, userId));
        }
        return effective;
    }

    /**
     * kick-off prompt（纯函数，可单测）。开头必须带 skill 触发词「股东大会核查」。
     */
    static String buildKickoffPrompt(String companyName, String stockCode, String meetingName,
                                     LocalDate meetingDate, Map<String, List<ProjectFile>> materials,
                                     Long workpaperFolderId, Long opinionFolderId) {
        StringBuilder sb = new StringBuilder();
        sb.append("股东大会核查：请对下列股东会执行完整核查，撰写法律意见书并生成核查底稿。\n\n");
        sb.append("【会议信息】\n");
        sb.append("- 公司：").append(companyName);
        if (StringUtils.hasText(stockCode)) sb.append("（股票代码 ").append(stockCode).append("）");
        sb.append("\n- 届次：").append(meetingName).append("\n");
        if (meetingDate != null) sb.append("- 召开日期：").append(meetingDate).append("\n");

        sb.append("\n【材料清单】（用 extract_file_text 按 fileId 读取全文）\n");
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<ProjectFile>> entry : materials.entrySet()) {
            if (entry.getValue().isEmpty()) {
                if (!"其他材料".equals(entry.getKey()) && !"意见书模板或会前初稿".equals(entry.getKey())) {
                    missing.add(entry.getKey());
                }
                continue;
            }
            for (ProjectFile f : entry.getValue()) {
                sb.append("- ").append(entry.getKey()).append("：fileId=").append(f.getId())
                        .append("《").append(f.getName()).append("》\n");
            }
        }

        if (!missing.isEmpty()) {
            sb.append("\n【缺失材料】").append(String.join("、", missing))
                    .append("——相应交叉核对无法进行，法律意见书与核查底稿中必须显式声明该部分未经交叉核对，请项目组自行核查。\n");
        }

        sb.append("\n【产出要求】\n");
        sb.append("- 交叉核对底稿表：用 write_docx 写入 parentFolderId=").append(workpaperFolderId)
                .append("，文件名「核查底稿_").append(companyName).append("_").append(meetingName).append(".docx」\n");
        sb.append("- 法律意见书：用 write_docx 写入 parentFolderId=").append(opinionFolderId)
                .append("，文件名「法律意见书_").append(companyName).append("_").append(meetingName).append(".docx」\n");
        return sb.toString();
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
