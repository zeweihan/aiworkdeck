package com.checkba.service.quota;

import com.checkba.exception.StageQuotaExceededException;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件缓存区（左下角「文件暂存区」）的免费额度（Spec §5）。
 *
 * <p>缓存区的物理形态是项目内一个名为 {@code __staging_area__} 的文件夹，
 * 「加入缓存区」= 把已有项目文件移进这个文件夹。因此额度的执行点是
 * <b>移入时拦截</b>：达到上限就拒绝这次移动，
 * <b>已经在缓存区里的文件一律不动、不删、不隐藏</b>。这是本功能的核心不变式——
 * 剪贴板那侧是「隐藏超出部分」，这侧是「不再新增」，两者都不碰用户已有数据。</p>
 *
 * <p>拥有 {@code stage.unlimited} 时完全不限制。</p>
 */
@Service
public class StageQuotaService {

    /** 缓存区文件夹名（与前端 stagingArea.js 的 folderName 必须一致）。 */
    public static final String STAGING_FOLDER_NAME = "__staging_area__";

    /** 免费版缓存区文件数上限。 */
    public static final int FREE_MAX_FILES = 20;

    /** 免费版缓存区总字节上限（500MB）。 */
    public static final long FREE_MAX_BYTES = 500L * 1024 * 1024;

    private final ProjectFileRepository projectFileRepository;
    private final EntitlementService entitlementService;

    public StageQuotaService(ProjectFileRepository projectFileRepository, EntitlementService entitlementService) {
        this.projectFileRepository = projectFileRepository;
        this.entitlementService = entitlementService;
    }

    /** 该文件夹是不是缓存区目录。 */
    public boolean isStagingFolder(Long folderId) {
        if (folderId == null) return false;
        return projectFileRepository.findById(folderId)
                .map(f -> Boolean.TRUE.equals(f.getIsFolder()) && STAGING_FOLDER_NAME.equals(f.getName()))
                .orElse(false);
    }

    /**
     * 移入缓存区前的准入检查。目标不是缓存区、或已拥有无限版时直接放行。
     *
     * @param targetParentId 本次移动的目标目录
     * @param incomingIds    本次要移入的文件 id
     * @throws StageQuotaExceededException 超出额度时抛出，此时一个文件都不会被移动
     */
    public void checkAdmission(Long targetParentId, List<Long> incomingIds) {
        if (entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED)) return;
        if (!isStagingFolder(targetParentId)) return;

        // 缓存区所属项目：跨项目的 id 一律不参与计算。批量移动的归属校验在
        // ProjectFileService 的循环里（逐个 move 之前），比本方法晚；不在这里划清项目边界的话，
        // 越权探测者能靠「是否被额度拒绝」推断出别人项目里某个文件的大小。
        Long stageProjectId = projectFileRepository.findById(targetParentId)
                .map(ProjectFile::getProjectId).orElse(null);

        List<ProjectFile> existing = currentFiles(targetParentId);
        long count = existing.size();
        long bytes = existing.stream().mapToLong(StageQuotaService::sizeOf).sum();

        // 已在缓存区里的文件重复拖入不算新增（前端多选里可能混着已在区内的文件）
        List<ProjectFile> incoming = new ArrayList<>();
        for (Long id : incomingIds == null ? List.<Long>of() : incomingIds) {
            if (id == null) continue;
            ProjectFile f = projectFileRepository.findById(id).orElse(null);
            if (f == null) continue;
            if (!Objects.equals(f.getProjectId(), stageProjectId)) continue;
            if (Objects.equals(f.getParentId(), targetParentId)) continue;
            incoming.add(f);
        }
        if (incoming.isEmpty()) return;

        long newCount = count + incoming.size();
        long newBytes = bytes + incoming.stream().mapToLong(StageQuotaService::sizeOf).sum();

        if (newCount > FREE_MAX_FILES) {
            throw new StageQuotaExceededException(
                    "文件缓存区免费版最多存放 " + FREE_MAX_FILES + " 个文件，当前已有 " + count
                            + " 个。已有文件不会被删除，可以先移出几个，或解锁无限版。",
                    count, bytes, FREE_MAX_FILES, FREE_MAX_BYTES);
        }
        if (newBytes > FREE_MAX_BYTES) {
            throw new StageQuotaExceededException(
                    "文件缓存区免费版总量上限 " + formatMb(FREE_MAX_BYTES) + "，当前已用 " + formatMb(bytes)
                            + "。已有文件不会被删除，可以先移出几个，或解锁无限版。",
                    count, bytes, FREE_MAX_FILES, FREE_MAX_BYTES);
        }
    }

    /**
     * 缓存区当前用量，供前端顶部用量条使用。
     * 上限字段在拥有无限版时为 null——前端据此不显示用量条。
     */
    public Map<String, Object> usage(Long stagingFolderId) {
        List<ProjectFile> existing = currentFiles(stagingFolderId);
        boolean unlimited = entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileCount", existing.size());
        result.put("totalBytes", existing.stream().mapToLong(StageQuotaService::sizeOf).sum());
        result.put("limited", !unlimited);
        result.put("maxFiles", unlimited ? null : FREE_MAX_FILES);
        result.put("maxBytes", unlimited ? null : FREE_MAX_BYTES);
        return result;
    }

    /** 缓存区里的文件（不含子文件夹本身，不含回收站里的）。 */
    private List<ProjectFile> currentFiles(Long stagingFolderId) {
        if (stagingFolderId == null) return List.of();
        return projectFileRepository.findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(
                        projectFileRepository.findById(stagingFolderId).map(ProjectFile::getProjectId).orElse(null),
                        stagingFolderId).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                .toList();
    }

    private static long sizeOf(ProjectFile f) {
        Long size = f.getFileSize();
        return size == null || size < 0 ? 0L : size;
    }

    private static String formatMb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0) + "MB";
    }
}
