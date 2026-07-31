package com.checkba.storage;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 锁定 ProjectStorageResolver 的三条核心语义：
 * 1. 命名空间路由：projects/{id}/ 按项目根解析，其余按全局根；
 * 2. localRoot 感知：有 localRoot 的项目解析进用户文件夹，无则回落托管路径；
 * 3. 越界围栏：normalize 后逃出对应根的 key 必须被拒绝。
 */
class ProjectStorageResolverTest {

    private StorageProperties props(Path root) {
        StorageProperties p = new StorageProperties();
        p.getLocal().setRootPath(root.toAbsolutePath().toString());
        p.getLocal().setTemplatePath(root.resolve("template.docx").toAbsolutePath().toString());
        return p;
    }

    @Test
    void globalNamespaceResolvesUnderGlobalRoot(@TempDir Path root) {
        ProjectStorageResolver r = new ProjectStorageResolver(props(root), null);
        assertEquals(root.resolve("avatars/1.png").normalize(), r.resolve("avatars/1.png"));
        assertEquals(root.resolve("clipboard/9/x").normalize(), r.resolve("clipboard/9/x"));
    }

    @Test
    void managedProjectResolvesUnderGlobalProjectsDir(@TempDir Path root) {
        ProjectStorageResolver r = new ProjectStorageResolver(props(root), null);
        assertEquals(root.resolve("projects/7/合同.docx").normalize(), r.resolve("projects/7/合同.docx"));
        assertEquals(root.resolve("projects/7").normalize(), r.projectRoot(7));
    }

    @Test
    void localRootProjectResolvesIntoUserFolder(@TempDir Path root, @TempDir Path userFolder) {
        ProjectRepository repo = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(7L);
        p.setLocalRoot(userFolder.toAbsolutePath().toString());
        when(repo.findById(7L)).thenReturn(Optional.of(p));

        ProjectStorageResolver r = new ProjectStorageResolver(props(root), repo);
        assertEquals(userFolder.resolve("合同.docx").normalize(), r.resolve("projects/7/合同.docx"));
        assertEquals(userFolder.resolve("sub/x.txt").normalize(), r.resolve("projects/7/sub/x.txt"));
        assertEquals(userFolder.normalize(), r.projectRoot(7));
        assertTrue(r.hasLocalRoot(7));
    }

    @Test
    void invalidateDropsCachedLocalRoot(@TempDir Path root, @TempDir Path userFolder) {
        ProjectRepository repo = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(7L);
        p.setLocalRoot(null);
        when(repo.findById(7L)).thenReturn(Optional.of(p));

        ProjectStorageResolver r = new ProjectStorageResolver(props(root), repo);
        assertEquals(root.resolve("projects/7").normalize(), r.projectRoot(7));

        p.setLocalRoot(userFolder.toAbsolutePath().toString());
        // 未失效前仍走缓存
        assertEquals(root.resolve("projects/7").normalize(), r.projectRoot(7));
        r.invalidate(7);
        assertEquals(userFolder.normalize(), r.projectRoot(7));
    }

    @Test
    void missingProjectRowIsNotCached(@TempDir Path root, @TempDir Path userFolder) {
        ProjectRepository repo = mock(ProjectRepository.class);
        when(repo.findById(7L)).thenReturn(Optional.empty());

        ProjectStorageResolver r = new ProjectStorageResolver(props(root), repo);
        assertEquals(root.resolve("projects/7").normalize(), r.projectRoot(7));

        // 项目行随后出现（创建窗口），无需 invalidate 也能解析到 localRoot
        Project p = new Project();
        p.setId(7L);
        p.setLocalRoot(userFolder.toAbsolutePath().toString());
        when(repo.findById(7L)).thenReturn(Optional.of(p));
        assertEquals(userFolder.normalize(), r.projectRoot(7));
    }

    @Test
    void rejectsTraversalOutOfGlobalRoot(@TempDir Path root) {
        ProjectStorageResolver r = new ProjectStorageResolver(props(root), null);
        assertThrows(StorageException.class, () -> r.resolve("../outside.txt"));
        assertThrows(StorageException.class, () -> r.resolve("avatars/../../outside.txt"));
    }

    @Test
    void rejectsTraversalOutOfLocalRoot(@TempDir Path root, @TempDir Path userFolder) {
        ProjectRepository repo = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(7L);
        p.setLocalRoot(userFolder.toAbsolutePath().toString());
        when(repo.findById(7L)).thenReturn(Optional.of(p));

        ProjectStorageResolver r = new ProjectStorageResolver(props(root), repo);
        assertThrows(StorageException.class, () -> r.resolve("projects/7/../../etc/passwd"));
        assertThrows(StorageException.class, () -> r.resolve("projects/7/a/../../b"));
    }

    @Test
    void rejectsBlankKey(@TempDir Path root) {
        ProjectStorageResolver r = new ProjectStorageResolver(props(root), null);
        assertThrows(StorageException.class, () -> r.resolve(""));
        assertThrows(StorageException.class, () -> r.resolve(null));
    }
}
