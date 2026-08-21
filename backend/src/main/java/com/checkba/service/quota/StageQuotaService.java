package com.checkba.service.quota;

import com.checkba.exception.StageQuotaExceededException;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文件缓存区（左下角「文件暂存区」）的免费额度（Spec §5）。
 *
 * <p>缓存区的物理形态是项目内一个名为 {@code __staging_area__} 的文件夹，
 * 「加入缓存区」= 把已有项目文件移进这个文件夹。因此额度的执行点是
 * <b>移入时拦截</b>：达到上限就拒绝这次移动，
 * <b>已经在缓存区里的文件一律不动、不删、不隐藏</b>。这是本功能的核心不变式——
 * 剪贴板那侧是「隐藏超出部分」，这侧是「不再新增」，两者都不碰用户已有数据。</p>
 *
 * <p>拥有 {@code stage.unlimited} 时完全不限制；
 * 非单机模式（团队案件库服务器）同样完全不限制，见 {@link #limited()}。</p>
 */
@Service
@Slf4j
public class StageQuotaService {

    /** 缓存区文件夹名（与前端 stagingArea.js 的 folderName 必须一致）。 */
    public static final String STAGING_FOLDER_NAME = "__staging_area__";

    /** 免费版缓存区文件数上限。 */
    public static final int FREE_MAX_FILES = 20;

    /** 免费版缓存区总字节上限（500MB）。 */
    public static final long FREE_MAX_BYTES = 500L * 1024 * 1024;

    /**
     * 递归统计时最多下钻的目录数。额度检查在每次移入的热路径上，
     * 脏数据里若存在父子成环（历史数据修复留下的）不能让它一直转下去。
     * 5000 个目录远超缓存区的正常规模，触顶只会让统计偏小（放行），不会误拦。
     */
    private static final int MAX_SCANNED_FOLDERS = 5000;

    private final ProjectFileRepository projectFileRepository;
    private final EntitlementService entitlementService;
    private final boolean localMode;

    public StageQuotaService(ProjectFileRepository projectFileRepository,
                             EntitlementService entitlementService,
                             @Value("${security.local-mode:false}") boolean localMode) {
        this.projectFileRepository = projectFileRepository;
        this.entitlementService = entitlementService;
        this.localMode = localMode;
    }

    /**
     * 现在是否要执行免费额度。
     *
     * <p>两个「不限制」的理由不同：</p>
     * <ul>
     *   <li>拥有 {@code stage.unlimited}：用户买了；</li>
     *   <li>非单机模式：{@link EntitlementService} 是<b>按本机</b>的（来源是本机
     *       {@code ~/.aiworkdeck} 的 license/account 状态，没有 userId 维度）。
     *       部署成团队案件库时服务器上根本不存在账户状态，权益恒为空——真照着执行，
     *       每个接入的成员都会被截到 20 个文件且永远无法解锁。这两个 SKU 卖的是
     *       单机版的本地能力，服务器部署一律不限，与 {@code LicenseController}
     *       「非 local-mode 恒为已解锁正式版」同口径。</li>
     * </ul>
     */
    private boolean limited() {
        if (!localMode) return false;
        return !entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED);
    }

    /** 该文件夹是不是缓存区目录。 */
    public boolean isStagingFolder(Long folderId) {
        if (folderId == null) return false;
        return projectFileRepository.findById(folderId)
                .map(f -> Boolean.TRUE.equals(f.getIsFolder()) && STAGING_FOLDER_NAME.equals(f.getName()))
                .orElse(false);
    }

    /**
     * 移入缓存区前的准入检查。目标不是缓存区、或不执行额度时直接放行。
     *
     * <p>文件夹按<b>它装的全部文件</b>计（递归）：文件树允许把整个文件夹拖进缓存区，
     * 若只把它算作 1 个条目 0 字节，拖一个装着几万个文件的目录进来就能让两条额度同时失效。</p>
     *
     * @param targetParentId 本次移动的目标目录
     * @param incomingIds    本次要移入的文件 id
     * @throws StageQuotaExceededException 超出额度时抛出，此时一个文件都不会被移动
     */
    public void checkAdmission(Long targetParentId, List<Long> incomingIds) {
        if (!limited()) return;
        if (!isStagingFolder(targetParentId)) return;

        // 悲观行锁：把接下来的「查当前用量 + 判断放行」钉进调用方（move/batchMove，都是
        // @Transactional）已经开着的那个事务里。这里不能用进程内锁（synchronized）代替——
        // 那种锁在本方法返回时就会释放，而 move/batchMove 真正的写入要等外层 @Transactional
        // 方法整体返回、AOP 代理提交时才落库；第二个并发请求拿到进程锁后即使重新查库，
        // 看到的仍是第一个请求尚未提交的旧用量，两边都会放行，合计超额（审计原话描述的
        // 竞态）。行锁不同：它与事务同生命周期，第二个请求的锁获取会一直阻塞到第一个请求
        // 的事务提交（或回滚）为止，锁到手时数据库里已经是提交后的真实用量。
        ProjectFile stagingFolder = projectFileRepository.lockById(targetParentId).orElse(null);

        // 缓存区所属项目：跨项目的 id 一律不参与计算。批量移动的归属校验在
        // ProjectFileService 的循环里（逐个 move 之前），比本方法晚；不在这里划清项目边界的话，
        // 越权探测者能靠「是否被额度拒绝」推断出别人项目里某个文件的大小。
        Long stageProjectId = stagingFolder == null ? null : stagingFolder.getProjectId();

        Subtree existing = subtree(stageProjectId, targetParentId);
        long count = existing.files().size();
        long bytes = existing.files().stream().mapToLong(StageQuotaService::sizeOf).sum();

        // 按 id 去重：多选里同时勾了某个文件夹和它里面的文件时，那些文件只能算一次
        Map<Long, ProjectFile> incoming = new LinkedHashMap<>();
        for (Long id : incomingIds == null ? List.<Long>of() : incomingIds) {
            if (id == null) continue;
            ProjectFile f = projectFileRepository.findById(id).orElse(null);
            if (f == null) continue;
            if (!Objects.equals(f.getProjectId(), stageProjectId)) continue;
            // 已经在缓存区里（含子目录里）的东西重复拖入不算新增
            if (existing.nodeIds().contains(id)) continue;
            if (Boolean.TRUE.equals(f.getIsFolder())) {
                for (ProjectFile inner : subtree(stageProjectId, id).files()) {
                    incoming.put(inner.getId(), inner);
                }
            } else {
                incoming.put(id, f);
            }
        }
        if (incoming.isEmpty()) return;

        long newCount = count + incoming.size();
        long newBytes = bytes + incoming.values().stream().mapToLong(StageQuotaService::sizeOf).sum();

        if (newCount > FREE_MAX_FILES) {
            throw new StageQuotaExceededException(
                    LangText.of(
                            "文件缓存区免费版最多存放 " + FREE_MAX_FILES + " 个文件，当前已有 " + count
                                    + " 个。已有文件不会被删除，可以先移出几个，或解锁无限版。",
                            "The free File Staging Area holds at most " + FREE_MAX_FILES + " files; you currently have " + count
                                    + ". Existing files won't be deleted — move some out first, or unlock the Unlimited edition."),
                    count, bytes, FREE_MAX_FILES, FREE_MAX_BYTES);
        }
        if (newBytes > FREE_MAX_BYTES) {
            throw new StageQuotaExceededException(
                    LangText.of(
                            "文件缓存区免费版总量上限 " + formatMb(FREE_MAX_BYTES) + "，当前已用 " + formatMb(bytes)
                                    + "。已有文件不会被删除，可以先移出几个，或解锁无限版。",
                            "The free File Staging Area caps total size at " + formatMb(FREE_MAX_BYTES) + "; you've used " + formatMb(bytes)
                                    + ". Existing files won't be deleted — move some out first, or unlock the Unlimited edition."),
                    count, bytes, FREE_MAX_FILES, FREE_MAX_BYTES);
        }
    }

    /**
     * 缓存区当前用量，供前端顶部用量条使用。
     * 上限字段在拥有无限版时为 null——前端据此不显示用量条。
     */
    public Map<String, Object> usage(Long stagingFolderId) {
        List<ProjectFile> existing = currentFiles(stagingFolderId);
        boolean limited = limited();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileCount", existing.size());
        result.put("totalBytes", existing.stream().mapToLong(StageQuotaService::sizeOf).sum());
        result.put("limited", limited);
        result.put("maxFiles", limited ? FREE_MAX_FILES : null);
        result.put("maxBytes", limited ? FREE_MAX_BYTES : null);
        return result;
    }

    /**
     * 缓存区里的文件（递归进子文件夹，不含文件夹自身，不含回收站里的）。
     * 与 {@link #checkAdmission} 同一口径——用量条显示的数字必须就是额度判定用的数字，
     * 否则会出现「条显示 3/20 却拒绝第 4 个」这种无法自证的状态。
     */
    private List<ProjectFile> currentFiles(Long stagingFolderId) {
        if (stagingFolderId == null) return List.of();
        Long projectId = projectFileRepository.findById(stagingFolderId)
                .map(ProjectFile::getProjectId).orElse(null);
        return subtree(projectId, stagingFolderId).files();
    }

    /**
     * 一棵子树的展开结果。
     *
     * @param files   树里的全部文件（不含文件夹自身）
     * @param nodeIds 树里的全部节点 id（含文件夹），用来判断某个 id 是不是已经在树里了
     */
    private record Subtree(List<ProjectFile> files, Set<Long> nodeIds) {}

    /** 广度优先展开 rootId 下的整棵树。rootId 本身不计入。 */
    private Subtree subtree(Long projectId, Long rootId) {
        List<ProjectFile> files = new ArrayList<>();
        Set<Long> nodeIds = new LinkedHashSet<>();
        if (rootId == null) return new Subtree(files, nodeIds);

        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visitedFolders = new HashSet<>();
        queue.add(rootId);
        visitedFolders.add(rootId);
        int scanned = 0;
        while (!queue.isEmpty()) {
            if (++scanned > MAX_SCANNED_FOLDERS) {
                log.warn("缓存区目录扫描触顶（{} 个目录），用量统计可能偏小: rootId={}", MAX_SCANNED_FOLDERS, rootId);
                break;
            }
            Long parentId = queue.poll();
            for (ProjectFile child : projectFileRepository
                    .findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(projectId, parentId)) {
                if (child.getId() == null) continue;
                nodeIds.add(child.getId());
                if (Boolean.TRUE.equals(child.getIsFolder())) {
                    if (visitedFolders.add(child.getId())) queue.add(child.getId());
                } else {
                    files.add(child);
                }
            }
        }
        return new Subtree(files, nodeIds);
    }

    private static long sizeOf(ProjectFile f) {
        Long size = f.getFileSize();
        return size == null || size < 0 ? 0L : size;
    }

    private static String formatMb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0) + "MB";
    }
}
