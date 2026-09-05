package com.checkba.service.maintenance;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 启动期对账：修 {@code project_file.parent_id = 0} 留下的孤儿行与它们催生的重复节点
 * （dev-board#457）。
 *
 * <h3>坏数据是怎么来的</h3>
 * Agent 的 {@code create_folder} 把「放项目根目录」写成 {@code parentFolderId=0}
 * （LLM 的惯用写法），而服务层此前不校验父节点，于是落了一批 {@code parent_id=0} 的行——
 * 库里并没有 id=0 这一行。前端 {@code normalizeParentId} 把 0 当根画出来，看着像普通根文件夹；
 * 后端的同名查重却把 0 与 NULL 当两个不同的父节点。本地文件夹对账器随后按
 * {@code rowKey("root/名字")} 找不到这条孤儿行，就再建一条真正的根行——资源管理器顶部
 * 于是多出一个重复节点，刷新、重启都在。写入侧的闸已经补上
 * （{@code ProjectFileService.resolveParentId}），但**存量行救不回来**：那批行就躺在
 * 每一台已经让 Agent 整理过文件的机器上。本仓没有 SQL 迁移框架（ddl-auto: update），
 * 所以照 {@code MediaFileTypeReconciler} 的成例做成启动期对账。
 *
 * <h3>做三件事，都只动数据库、一个字节都不碰磁盘</h3>
 * <ol>
 *   <li><b>归位</b>：{@code parent_id=0} 的行，根下没有同名同类的存活行时，直接把
 *       parent_id 改成 null——它本来要的就是「根」；</li>
 *   <li><b>并档</b>：根下已经有同名文件夹（那条正是对账器补出来的重复节点）时，
 *       把孤儿文件夹的子项挂到根文件夹下，子项同名冲突时保留根文件夹里已有的那份、
 *       软删除孤儿这份，最后软删除空掉的孤儿文件夹；</li>
 *   <li><b>去重</b>：同一项目内 file_path 相同的多条存活文件行只留 id 最小的一条，
 *       其余软删除——它们指向同一份字节，留着改名/删除任何一条都会伤到另一条。</li>
 * </ol>
 *
 * <p><b>为什么不用搬文件</b>：孤儿行的物理路径在缺失的父节点处就断链了
 * （{@code buildPhysicalPath} 查不到 id=0 便 break），算出来的正是「根 / 文件夹名 / 文件名」，
 * 与并档目标那条根行算出的字符串逐字相同。归位与并档因此都不改变任何一行的 file_path，
 * 磁盘上的目录与文件原封不动。
 *
 * <p><b>回收站里的孤儿只归位不并档</b>：它们已经是删除态，界面上看不见，没有并档的必要；
 * 但把 0 归成 null 能保证律师从回收站还原它时不会又得到一条谁也点不开的孤儿行。
 * 根下已有同名存活行时连归位也不做——那属于还原时才该让用户看见的普通重名。
 *
 * <p>幂等：跑完一遍之后 parent_id=0 的行与重复路径都不存在了，之后每次启动都是 0 条，
 * 健康安装里连一条 INFO 都不会打。失败只 warn，不拖垮启动。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrphanParentReconciler {

    /** 模型/前端表达「项目根」的另一种写法，库里并没有这一行。 */
    private static final long ORPHAN_PARENT_ID = 0L;

    private final ProjectFileRepository projectFileRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            // 对账是顺手活，读不到文件表不该拦住启动
            log.warn("孤儿父节点对账跳过：{}", e.toString());
        }
    }

    /** @return 被改写（含软删除）的行数，供测试与日志用 */
    public int reconcile() {
        int changed = healOrphanRows() + dedupeByFilePath();
        if (changed > 0) {
            log.info("孤儿父节点对账完成：共处理 {} 行", changed);
        }
        return changed;
    }

    // ---- (a)(b) parent_id = 0 的孤儿行 ----

    private int healOrphanRows() {
        List<ProjectFile> orphans = projectFileRepository.findByParentId(ORPHAN_PARENT_ID);
        int changed = 0;
        for (ProjectFile orphan : orphans) {
            ProjectFile twin = liveRootTwinOf(orphan);
            if (Boolean.TRUE.equals(orphan.getIsDeleted())) {
                if (twin == null) {
                    changed += moveToRoot(orphan, "回收站行");
                }
                continue;
            }
            if (twin == null) {
                changed += moveToRoot(orphan, "根下无同名行");
                continue;
            }
            if (Boolean.TRUE.equals(orphan.getIsFolder())) {
                changed += mergeFolderInto(orphan, twin);
            } else {
                // 文件孤儿与根下同名文件指向同一份字节（路径断链后算出的是同一个字符串），
                // 保留根下那条真行，孤儿这条进回收站；字节不动。
                changed += softDelete(orphan, "与根下同名文件重复（file_path 相同）");
            }
        }
        return changed;
    }

    /** 根下同名同类（文件夹对文件夹、文件对文件）的存活行。 */
    private ProjectFile liveRootTwinOf(ProjectFile orphan) {
        List<ProjectFile> rootRows =
                projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(orphan.getProjectId(), null);
        for (ProjectFile r : rootRows) {
            if (!r.getId().equals(orphan.getId())
                    && r.getName() != null && r.getName().equals(orphan.getName())
                    && Boolean.TRUE.equals(r.getIsFolder()) == Boolean.TRUE.equals(orphan.getIsFolder())) {
                return r;
            }
        }
        return null;
    }

    private int moveToRoot(ProjectFile orphan, String why) {
        orphan.setParentId(null);
        orphan.setUpdatedAt(LocalDateTime.now());
        projectFileRepository.save(orphan);
        log.info("孤儿父节点对账：{} {}（{}）parent_id 0 → null（{}）",
                Boolean.TRUE.equals(orphan.getIsFolder()) ? "文件夹" : "文件",
                orphan.getId(), orphan.getName(), why);
        return 1;
    }

    /**
     * 把孤儿文件夹并进根下那个同名文件夹：子项改挂过去（同名的让位给已有那份），
     * 然后软删除空掉的孤儿。两个文件夹同名，物理路径不变，不搬任何文件。
     */
    private int mergeFolderInto(ProjectFile orphan, ProjectFile target) {
        int changed = 0;
        // 复制一份：下面 add 进来的是「刚改挂过去的子项」，要参与后续同名判断
        List<ProjectFile> targetChildren = new ArrayList<>(
                projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(target.getProjectId(), target.getId()));
        for (ProjectFile child : projectFileRepository.findByParentId(orphan.getId())) {
            if (Boolean.TRUE.equals(child.getIsDeleted())) continue;
            boolean taken = targetChildren.stream().anyMatch(
                    t -> t.getName() != null && t.getName().equals(child.getName()));
            if (taken) {
                changed += softDeleteRecursively(child, "并档时与目标文件夹里已有的同名项重复");
            } else {
                child.setParentId(target.getId());
                child.setUpdatedAt(LocalDateTime.now());
                projectFileRepository.save(child);
                targetChildren.add(child);
                changed++;
                log.info("孤儿父节点对账：{}（{}）从孤儿文件夹 {} 改挂到根下同名文件夹 {}",
                        Boolean.TRUE.equals(child.getIsFolder()) ? "文件夹" : "文件",
                        child.getId(), orphan.getId(), target.getId());
            }
        }
        changed += softDelete(orphan, "已并入根下同名文件夹 " + target.getId());
        return changed;
    }

    // ---- (c) 同一项目内重复的 file_path ----

    private int dedupeByFilePath() {
        int changed = 0;
        for (Object[] row : projectFileRepository.findDuplicateLiveFilePaths()) {
            Long projectId = (Long) row[0];
            String filePath = (String) row[1];
            List<ProjectFile> rows = new ArrayList<>(
                    projectFileRepository.findByProjectIdAndFilePathAndIsDeletedFalse(projectId, filePath));
            rows.removeIf(f -> Boolean.TRUE.equals(f.getIsFolder()));
            rows.sort(Comparator.comparing(ProjectFile::getId));
            for (int i = 1; i < rows.size(); i++) {
                changed += softDelete(rows.get(i),
                        "与文件行 " + rows.get(0).getId() + " 指向同一份字节 " + filePath);
            }
        }
        return changed;
    }

    // ---- 软删除（只翻标志位，磁盘文件保留，用户可从回收站还原）----

    private int softDelete(ProjectFile row, String why) {
        if (Boolean.TRUE.equals(row.getIsDeleted())) return 0;
        row.setIsDeleted(true);
        row.setDeletedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        projectFileRepository.save(row);
        log.info("孤儿父节点对账：{}（{}）移入回收站（{}）；磁盘文件保留",
                Boolean.TRUE.equals(row.getIsFolder()) ? "文件夹" : "文件", row.getId(), why);
        return 1;
    }

    /** 文件夹连同其下存活的子孙一起软删除，避免又造出一批父节点已删的孤儿。 */
    private int softDeleteRecursively(ProjectFile row, String why) {
        int changed = softDelete(row, why);
        if (!Boolean.TRUE.equals(row.getIsFolder())) return changed;
        for (ProjectFile child : projectFileRepository.findByParentId(row.getId())) {
            if (Boolean.TRUE.equals(child.getIsDeleted())) continue;
            changed += softDeleteRecursively(child, "父文件夹 " + row.getId() + " 已随并档移入回收站");
        }
        return changed;
    }
}
