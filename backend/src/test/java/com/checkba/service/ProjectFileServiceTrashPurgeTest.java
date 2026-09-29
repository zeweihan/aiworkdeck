// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.storage.LocalFileStorageService;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 彻底删除送系统废纸篓（dev-board#1051）的服务层两半：
 * {@code diskPathsForPurge} 报出与磁盘删除同一份判定的物理绝对路径（文件夹目录覆盖的子路径折叠掉）；
 * {@code permDelete(id, user, diskHandled=true)} 只清行、一个字节都不碰磁盘。
 * 存储用真的 {@link LocalFileStorageService} 落在临时目录，判据是磁盘上的字节还在不在。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceTrashPurgeTest {

    @Mock private ProjectFileRepository repo;
    @Mock private ProjectRagService projectRagService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private com.checkba.version.WorkSessionService workSessionService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private UserService userService;

    @InjectMocks private ProjectFileService service;

    @TempDir Path root;
    private LocalFileStorageService storage;
    private final List<ProjectFile> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        props.getLocal().setTemplatePath(root.resolve("no-template.docx").toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, null);
        storage = new LocalFileStorageService(resolver);
        service.setStorageResolverForTest(resolver);
        when(storageServiceFactory.getStorageService()).thenReturn(storage);

        when(repo.findById(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByProjectIdAndParentId(anyLong(), anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> inv.getArgument(1).equals(r.getParentId())).toList());
        when(repo.findByProjectIdAndFilePathAndIsDeletedFalse(anyLong(), anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsDeleted()) && inv.getArgument(1).equals(r.getFilePath()))
                .toList());
        when(repo.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsDeleted())).toList());
        org.mockito.Mockito.doAnswer(inv -> rows.removeIf(r -> r.getId().equals(inv.getArgument(0))))
                .when(repo).deleteById(any());
    }

    private ProjectFile folder(long id, Long parentId, String name, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(1L);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(true);
        f.setIsDeleted(deleted);
        rows.add(f);
        return f;
    }

    private ProjectFile file(long id, Long parentId, String path, boolean deleted, String content) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(1L);
        f.setParentId(parentId);
        f.setName(path.substring(path.lastIndexOf('/') + 1));
        f.setIsFolder(false);
        f.setIsDeleted(deleted);
        f.setFilePath(path);
        rows.add(f);
        storage.save(path, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return f;
    }

    private Path disk(String key) {
        return root.resolve(key).toAbsolutePath().normalize();
    }

    @Test
    void diskHandledPurgeRemovesRowsButLeavesEveryByteOnDisk() throws Exception {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        file(12L, null, "projects/1/b.docx", true, "old-b");

        service.permDelete(10L, 9L, true);
        service.permDelete(12L, 9L, true);

        assertTrue(rows.isEmpty(), "行要清干净: " + rows);
        assertEquals("old-a", Files.readString(disk("projects/1/A/a.docx")), "磁盘归桌面壳处理，这里一个字节都不能动");
        assertEquals("old-b", Files.readString(disk("projects/1/b.docx")));
        assertTrue(Files.isDirectory(disk("projects/1/A")), "文件夹目录也不能删");
    }

    @Test
    void diskHandledFalseStillDeletesFromDisk() {
        file(12L, null, "projects/1/b.docx", true, "old-b");

        service.permDelete(12L, 9L, false);

        assertTrue(rows.isEmpty());
        assertFalse(Files.exists(disk("projects/1/b.docx")), "diskHandled=false 与旧行为一致");
    }

    @Test
    void diskPathsOfFolderCollapseToTheFolderDirectory() {
        folder(10L, null, "A", true);
        folder(13L, 10L, "sub", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        file(14L, 13L, "projects/1/A/sub/c.docx", true, "old-c");

        List<Path> paths = service.diskPathsForPurge(10L);

        assertEquals(List.of(disk("projects/1/A")), paths, "移走文件夹目录已经带走子孙，不能逐个再报");
        assertEquals(4, rows.size(), "只读：不删行");
        assertTrue(Files.exists(disk("projects/1/A/a.docx")), "只读：不碰磁盘");
    }

    @Test
    void diskPathsOfSingleFileIsItsAbsolutePath() {
        file(12L, null, "projects/1/b.docx", true, "old-b");
        assertEquals(List.of(disk("projects/1/b.docx")), service.diskPathsForPurge(12L));
    }

    /** 与 dev-board#1020 同一条判定：新 A 占着同一个目录与同名文件，只报旧 A 独有的那份。 */
    @Test
    void diskPathsSkipPathsStillUsedByLiveSameNameRows() {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        file(15L, 10L, "projects/1/A/only-old.docx", true, "old-x");
        folder(20L, null, "A", false);
        file(21L, 20L, "projects/1/A/a.docx", false, "live-a");

        assertEquals(List.of(disk("projects/1/A/only-old.docx")), service.diskPathsForPurge(10L));
    }

    @Test
    void diskPathsOfGoneRowIsEmpty() {
        assertEquals(List.of(), service.diskPathsForPurge(404L));
    }
}
