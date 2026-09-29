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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 彻底删除回收站里的旧文件夹，不能伤到后来在同一位置新建的同名文件夹（dev-board#1020）。
 *
 * <p>软删除只翻 isDeleted、不动磁盘；{@code createFolder} 与 {@code createFile(FAIL)} 的同名查重
 * 只看活着的行，所以「删 A → 再建 A」得到的新 A 与回收站里的旧 A 物理目录是同一个，
 * 新 A 里与旧 A 子文件同名的文件，filePath 也逐字相同。彻底删除旧 A 时按路径删，
 * 删掉的就是活着的那一份字节（数据库行还在，点开即「文件不存在」）。
 *
 * <p>存储用真的 {@link LocalFileStorageService} 落在临时目录：判据是磁盘上的字节还在不在，
 * 不是 delete 被传了什么参数——目录删不删得掉取决于存储实现是不是递归删除。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServicePurgeSameNameTest {

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
        storage = new LocalFileStorageService(new ProjectStorageResolver(props, null));
        when(storageServiceFactory.getStorageService()).thenReturn(storage);

        // 用一张内存表当仓库：deleteById 真的把行拿掉，查询都从这张表里现算
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
        return root.resolve(key);
    }

    /** 题面原样：新 A 里放的是另一个名字的文件——本地存储的目录删除不递归，本来就撞不上。 */
    @Test
    void purgingOldFolderKeepsDifferentlyNamedFileInNewSameNameFolder() throws Exception {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        folder(20L, null, "A", false);
        file(21L, 20L, "projects/1/A/b.docx", false, "live-b");

        service.permDelete(10L, 9L);

        assertFalse(Files.exists(disk("projects/1/A/a.docx")), "旧 A 的子文件按自己的路径删掉");
        assertEquals("live-b", Files.readString(disk("projects/1/A/b.docx")), "新 A 里的 b.docx 字节必须还在");
    }

    /** 真正撞上的那一种：新 A 里有一个与旧 A 子文件同名的文件，filePath 逐字相同。 */
    @Test
    void purgingOldFolderKeepsSameNamedFileInNewSameNameFolder() throws Exception {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        folder(20L, null, "A", false);
        file(21L, 20L, "projects/1/A/a.docx", false, "live-a");

        service.permDelete(10L, 9L);

        assertTrue(Files.exists(disk("projects/1/A/a.docx")), "新 A 里活着的 a.docx 不能被删");
        assertEquals("live-a", Files.readString(disk("projects/1/A/a.docx")));
    }

    /** 同一件事落在单个文件上：回收站里的 a.docx 与后来新建的 a.docx 共用一个路径。 */
    @Test
    void purgingDeletedFileKeepsLiveFileAtSamePath() throws Exception {
        file(30L, null, "projects/1/a.docx", true, "old");
        file(31L, null, "projects/1/a.docx", false, "live");

        service.permDelete(30L, 9L);

        assertEquals("live", Files.readString(disk("projects/1/a.docx")));
    }

    /** 新 A 是空的：目录本身也不能删——本地文件夹项目里目录没了，对账会把活着的新 A 判成已删除。 */
    @Test
    void purgingOldFolderKeepsEmptyDirectoryOfNewSameNameFolder() throws Exception {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");
        folder(20L, null, "A", false);

        service.permDelete(10L, 9L);

        assertTrue(Files.isDirectory(disk("projects/1/A")), "活着的新 A 的目录要留着");
    }

    /** 没有同名的活文件夹时，旧目录照常清理掉（修复不能变成「永远不删目录」）。 */
    @Test
    void purgingFolderWithoutLiveTwinStillRemovesItsDirectory() {
        folder(10L, null, "A", true);
        file(11L, 10L, "projects/1/A/a.docx", true, "old-a");

        service.permDelete(10L, 9L);

        assertFalse(Files.exists(disk("projects/1/A")));
    }
}
