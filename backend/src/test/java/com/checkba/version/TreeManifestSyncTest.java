package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TreeManifestSyncTest {

    private Map<Long, ProjectFile> db;
    private ProjectFileRepository repo;
    private ProjectTreeManifestService svc;
    private long nextId;

    private ProjectFile f(Long id, Long parentId, String name, boolean folder,
                          String path, boolean deleted, int sortOrder) {
        ProjectFile p = new ProjectFile();
        p.setId(id); p.setProjectId(7L); p.setParentId(parentId); p.setName(name);
        p.setIsFolder(folder); p.setFileType(folder ? null : "docx");
        p.setSortOrder(sortOrder); p.setFilePath(path); p.setIsDeleted(deleted);
        p.setUserId(1L);
        return p;
    }

    private TreeManifest.Node n(Long id, Long parentId, String name, boolean folder,
                                String path, boolean deleted, int sortOrder) {
        return n(id, parentId, name, folder, path, deleted, sortOrder, 1L);
    }

    private TreeManifest.Node n(Long id, Long parentId, String name, boolean folder,
                                String path, boolean deleted, int sortOrder, Long userId) {
        return new TreeManifest.Node(id, parentId, name, folder,
                folder ? null : "docx", sortOrder, path, deleted, userId,
                null, null, null, null);
    }

    @BeforeEach
    void setUp(@TempDir Path root) {
        db = new HashMap<>();
        nextId = 100L;
        repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectId(7L)).thenAnswer(i -> new ArrayList<>(db.values()));
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(db.get(i.getArgument(0))));
        when(repo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextId++);
            db.put(p.getId(), p);
            return p;
        });
        when(repo.existsById(any())).thenAnswer(i -> db.containsKey(i.getArgument(0)));

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        svc = new ProjectTreeManifestService(repo, new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null)), new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));
    }

    @Test
    void createsMissingNode() {
        TreeManifest m = new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0)));

        var r = svc.applyToDatabase(7L, m);

        assertEquals(1, r.created());
        assertEquals("合同.docx", db.get(1L).getName());
    }

    @Test
    void createsMissingNodeWithUserIdFromManifest() {
        // 新建节点的创建者必须取自清单，不能悄悄落到硬编码的 1L 或任何别的默认值。
        TreeManifest m = new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0, 42L)));

        svc.applyToDatabase(7L, m);

        assertEquals(42L, db.get(1L).getUserId(), "新建节点的 userId 必须采用清单里的值");
    }

    @Test
    void softDeletesNodeAbsentFromManifest() {
        db.put(1L, f(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0));
        db.put(2L, f(2L, null, "多余.docx", false, "projects/7/多余.docx", false, 1));

        var r = svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0))));

        assertEquals(1, r.softDeleted());
        assertTrue(db.get(2L).getIsDeleted(), "清单里没有的节点应进回收站");
        assertNotNull(db.get(2L), "不得物理删除");
    }

    @Test
    void updatesRenamedNode() {
        db.put(1L, f(1L, null, "旧名.docx", false, "projects/7/旧名.docx", false, 0));

        var r = svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "新名.docx", false, "projects/7/新名.docx", false, 0))));

        assertEquals(1, r.updated());
        assertEquals("新名.docx", db.get(1L).getName());
        assertEquals("projects/7/新名.docx", db.get(1L).getFilePath());
    }

    @Test
    void updatesMovedNode() {
        db.put(1L, f(1L, null, "文件夹", true, null, false, 0));
        db.put(2L, f(2L, null, "合同.docx", false, "projects/7/合同.docx", false, 1));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "文件夹", true, null, false, 0),
                n(2L, 1L, "合同.docx", false, "projects/7/文件夹/合同.docx", false, 1))));

        assertEquals(1L, db.get(2L).getParentId());
    }

    @Test
    void restoresNodeFromRecycleBin() {
        db.put(1L, f(1L, null, "合同.docx", false, "projects/7/合同.docx", true, 0));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0))));

        assertFalse(db.get(1L).getIsDeleted(), "清单里未删除的节点应从回收站恢复");
    }

    @Test
    void remapsParentIdWhenOriginalIdTaken() {
        // 数据库里 id=1 已被别的记录占用，清单里的 1 号节点必须新建并改写子节点 parentId
        db.put(1L, f(1L, null, "占位者.docx", false, "projects/7/占位者.docx", false, 0));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "文件夹", true, null, false, 0),
                n(2L, 1L, "合同.docx", false, "projects/7/文件夹/合同.docx", false, 1))));

        ProjectFile folder = db.values().stream()
                .filter(p -> "文件夹".equals(p.getName())).findFirst().orElseThrow();
        ProjectFile child = db.values().stream()
                .filter(p -> "合同.docx".equals(p.getName())).findFirst().orElseThrow();

        assertNotEquals(1L, folder.getId(), "被占用的 id 应改为新建");
        assertEquals(folder.getId(), child.getParentId(), "子节点 parentId 必须跟着重映射");
    }

    // ---- 清单并集（采纳一稿时用；与上面的同步语义刻意不同） -------------------

    @Test
    void unionApplyKeepsRowsAbsentFromManifest() {
        // 并集与同步的分水岭：清单里没有的行是「主线独有的文件」，绝不能进回收站。
        db.put(1L, f(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0));
        db.put(2L, f(2L, null, "主线独有.docx", false, "projects/7/主线独有.docx", false, 1));

        var r = svc.unionApply(7L, new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0))));

        assertEquals(0, r.softDeleted());
        assertFalse(db.get(2L).getIsDeleted(), "清单里没有的行必须原样保留");
    }

    @Test
    void unionApplyCreatesAndUpdatesFromManifest() {
        db.put(1L, f(1L, null, "旧名.docx", false, "projects/7/旧名.docx", false, 0));

        var r = svc.unionApply(7L, new TreeManifest(1, List.of(
                n(1L, null, "新名.docx", false, "projects/7/新名.docx", false, 0),
                n(9L, null, "稿新增.docx", false, "projects/7/稿新增.docx", false, 1))));

        assertEquals(1, r.updated());
        assertEquals(1, r.created());
        assertEquals("新名.docx", db.get(1L).getName(), "稿侧属性应覆盖同一行");
        assertEquals("稿新增.docx", db.get(9L).getName(), "稿独有的行应新建");
    }

    @Test
    void unionApplyRestoresFromRecycleBinButNeverSendsAnActiveRowThere() {
        // 稿上新建的文件在切回主线时被同步判成回收站状态，采纳时必须靠并集恢复。
        db.put(1L, f(1L, null, "稿新增.docx", false, "projects/7/稿新增.docx", true, 0));
        // 反向：主线独有的行在稿的清单里天然带着"已删除"的印子（切到稿上时被同步标掉，
        // 随后稿上的自动存档又把这个状态写进了稿的清单）——并集只加不减，绝不能照单执行。
        db.put(2L, f(2L, null, "主线独有.docx", false, "projects/7/主线独有.docx", false, 1));

        svc.unionApply(7L, new TreeManifest(1, List.of(
                n(1L, null, "稿新增.docx", false, "projects/7/稿新增.docx", false, 0),
                n(2L, null, "主线独有.docx", false, "projects/7/主线独有.docx", true, 1))));

        assertFalse(db.get(1L).getIsDeleted(), "稿侧标为在用的行应从回收站恢复");
        assertFalse(db.get(2L).getIsDeleted(), "并集只加不减：在用的行不能被稿的清单送进回收站");
    }
}
