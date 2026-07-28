package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文件树清单：把数据库里的文件树随版本一起存进仓库，
 * 使「退回到某一版」能同时还原目录结构、排序与回收站状态。
 */
@Service
public class ProjectTreeManifestService {

    public static final String MANIFEST_PATH = ".awd/tree.json";

    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepoService repoService;
    private final ObjectMapper objectMapper;

    public ProjectTreeManifestService(ProjectFileRepository projectFileRepository,
                                      ProjectRepoService repoService,
                                      ObjectMapper objectMapper) {
        this.projectFileRepository = projectFileRepository;
        this.repoService = repoService;
        this.objectMapper = objectMapper;
    }

    /** 从数据库采集当前文件树。软删除的节点也要收进来，否则回退无法还原回收站状态。 */
    public TreeManifest capture(long projectId) {
        try {
            List<TreeManifest.Node> nodes = projectFileRepository.findByProjectId(projectId)
                    .stream()
                    .sorted(Comparator.comparing(ProjectFile::getId))
                    .map(f -> new TreeManifest.Node(
                            f.getId(),
                            f.getParentId(),
                            f.getName(),
                            Boolean.TRUE.equals(f.getIsFolder()),
                            f.getFileType(),
                            f.getSortOrder(),
                            f.getFilePath(),
                            Boolean.TRUE.equals(f.getIsDeleted())))
                    .toList();
            return new TreeManifest(TreeManifest.CURRENT_VERSION, nodes);
        } catch (Exception e) {
            throw new VersionException("采集文件树清单失败: project=" + projectId, e);
        }
    }

    public void writeToWorkTree(long projectId, TreeManifest manifest) {
        try {
            Path target = repoService.workTree(projectId).resolve(MANIFEST_PATH);
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VersionException("写入文件树清单失败: project=" + projectId, e);
        }
    }

    /** 读取某一版的清单；该版没有清单（例如开启版本记录之前的历史）时返回 null。 */
    public TreeManifest readAtRef(long projectId, String ref) {
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, MANIFEST_PATH);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                    TreeManifest.class);
        } catch (Exception e) {
            throw new VersionException("解析文件树清单失败: project=" + projectId + " ref=" + ref, e);
        }
    }

    public record SyncReport(int created, int updated, int softDeleted) {}

    /**
     * 把清单描述的文件树同步进数据库。差异同步，不是删表重建——
     * 保证回退不会丢掉当前状态里的任何东西，且回退本身可以再回退。
     */
    public SyncReport applyToDatabase(long projectId, TreeManifest manifest) {
        try {
            Map<Long, ProjectFile> current = new HashMap<>();
            for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
                current.put(f.getId(), f);
            }

            // 清单节点按「父先于子」排序，保证建父节点时 parentId 已可解析
            List<TreeManifest.Node> ordered = topoSort(manifest.nodes());

            Map<Long, Long> remap = new LinkedHashMap<>();
            int created = 0, updated = 0;

            for (TreeManifest.Node node : ordered) {
                Long targetParentId = node.parentId() == null
                        ? null : remap.getOrDefault(node.parentId(), node.parentId());

                ProjectFile existing = current.get(node.id());
                boolean idTakenByOther = existing != null && !sameNode(existing, node);

                if (existing != null && !idTakenByOther) {
                    if (applyAttributes(existing, node, targetParentId)) {
                        projectFileRepository.save(existing);
                        updated++;
                    }
                    current.remove(node.id());
                    continue;
                }

                ProjectFile fresh = new ProjectFile();
                if (existing == null) fresh.setId(node.id());
                fresh.setProjectId(projectId);
                fresh.setUserId(existing != null ? existing.getUserId() : 1L);
                fresh.setCreatedAt(LocalDateTime.now());
                applyAttributes(fresh, node, targetParentId);
                ProjectFile saved = projectFileRepository.save(fresh);
                if (!Objects.equals(saved.getId(), node.id())) {
                    remap.put(node.id(), saved.getId());
                }
                created++;
                current.remove(node.id());
            }

            int softDeleted = 0;
            for (ProjectFile leftover : current.values()) {
                if (Boolean.TRUE.equals(leftover.getIsDeleted())) continue;
                leftover.setIsDeleted(true);
                leftover.setDeletedAt(LocalDateTime.now());
                projectFileRepository.save(leftover);
                softDeleted++;
            }

            return new SyncReport(created, updated, softDeleted);
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("同步文件树清单回数据库失败: project=" + projectId, e);
        }
    }

    /**
     * 同类型（文件夹/文件）即认为是同一个节点，改名、移动、改路径都算属性更新；
     * 类型都对不上，说明这个 id 已经被别的记录占用了，只能新建。
     * 不用 name 参与判断——改名恰恰是清单要更新的属性之一，用它做身份判据会把
     * 每一次改名都误判成「id 被占用」。
     */
    private boolean sameNode(ProjectFile f, TreeManifest.Node n) {
        return Boolean.TRUE.equals(f.getIsFolder()) == n.isFolder();
    }

    /** 返回 true 表示确实改动了字段。 */
    private boolean applyAttributes(ProjectFile f, TreeManifest.Node n, Long parentId) {
        boolean changed = false;
        if (!Objects.equals(f.getParentId(), parentId)) { f.setParentId(parentId); changed = true; }
        if (!Objects.equals(f.getName(), n.name())) { f.setName(n.name()); changed = true; }
        if (!Objects.equals(Boolean.TRUE.equals(f.getIsFolder()), n.isFolder())) {
            f.setIsFolder(n.isFolder()); changed = true;
        }
        if (!Objects.equals(f.getFileType(), n.fileType())) { f.setFileType(n.fileType()); changed = true; }
        if (!Objects.equals(f.getSortOrder(), n.sortOrder())) { f.setSortOrder(n.sortOrder()); changed = true; }
        if (!Objects.equals(f.getFilePath(), n.filePath())) { f.setFilePath(n.filePath()); changed = true; }
        if (Boolean.TRUE.equals(f.getIsDeleted()) != n.isDeleted()) {
            f.setIsDeleted(n.isDeleted());
            f.setDeletedAt(n.isDeleted() ? LocalDateTime.now() : null);
            changed = true;
        }
        if (changed) f.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    /**
     * 父节点排在子节点之前；环或悬空父节点按原顺序兜底附加。
     *
     * 用显式递归的私有方法代替 brief 里 Consumer[] 数组自引用的写法——
     * 语义等价（先访问父节点再把自己放进结果，visiting 集合防环），
     * 但不需要那个数组技巧，也没有 unchecked 警告。
     */
    private List<TreeManifest.Node> topoSort(List<TreeManifest.Node> nodes) {
        Map<Long, TreeManifest.Node> byId = new LinkedHashMap<>();
        for (TreeManifest.Node n : nodes) byId.put(n.id(), n);

        List<TreeManifest.Node> out = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        Set<Long> visiting = new HashSet<>();

        for (TreeManifest.Node n : nodes) visitForTopoSort(n, byId, placed, visiting, out);
        for (TreeManifest.Node n : nodes) if (!placed.contains(n.id())) out.add(n);
        return out;
    }

    private void visitForTopoSort(TreeManifest.Node node, Map<Long, TreeManifest.Node> byId,
                                   Set<Long> placed, Set<Long> visiting, List<TreeManifest.Node> out) {
        if (node == null || placed.contains(node.id()) || !visiting.add(node.id())) return;
        if (node.parentId() != null) visitForTopoSort(byId.get(node.parentId()), byId, placed, visiting, out);
        visiting.remove(node.id());
        if (placed.add(node.id())) out.add(node);
    }
}
