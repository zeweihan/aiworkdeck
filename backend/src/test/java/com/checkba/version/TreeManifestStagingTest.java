// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 退回 / 切线（{@code applyToDatabase}，同步语义）不许把根级文件缓存区送进回收站（dev-board#1021）。
 *
 * <p>缓存区是工作台每次打开懒建的运行时结构，不是版本内容：目标版本的清单常常早于它
 * 出生（或记着的是一个早已换掉的旧缓存区），照同步语义把「清单里没有的行」一律软删，
 * 每退回 / 切一次版本回收站里就多一个空壳，工作台下次打开再懒建一个新的。
 */
class TreeManifestStagingTest {

    private static final String STAGING = "__staging_area__";

    private Map<Long, ProjectFile> db;
    private ProjectTreeManifestService svc;
    private long nextId;

    private ProjectFile f(Long id, Long parentId, String name, boolean folder, boolean deleted) {
        ProjectFile p = new ProjectFile();
        p.setId(id); p.setProjectId(7L); p.setParentId(parentId); p.setName(name);
        p.setIsFolder(folder); p.setFileType(folder ? null : "docx");
        p.setSortOrder(0); p.setIsDeleted(deleted); p.setUserId(1L);
        if (!folder) p.setFilePath("projects/7/" + name);
        return p;
    }

    private TreeManifest.Node n(Long id, Long parentId, String name, boolean folder, boolean deleted) {
        return new TreeManifest.Node(id, parentId, name, folder, folder ? null : "docx", 0,
                folder ? null : "projects/7/" + name, deleted, 1L, null, null, null, null);
    }

    private long liveRootStagingCount() {
        return db.values().stream().filter(p -> p.getParentId() == null && Boolean.TRUE.equals(p.getIsFolder())
                && STAGING.equals(p.getName()) && !Boolean.TRUE.equals(p.getIsDeleted())).count();
    }

    @BeforeEach
    void setUp(@TempDir Path root) {
        db = new HashMap<>();
        nextId = 100L;
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
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
        svc = new ProjectTreeManifestService(repo, new ProjectRepoService(new ProjectStorageResolver(props, null)),
                new ObjectMapper(), mock(UserRepository.class), mock(ProjectRepository.class));
    }

    /** 目标版本早于缓存区出生：清单里没有它，退回后它仍然活着。 */
    @Test
    void liveStagingAbsentFromTargetManifestIsNotSoftDeleted() {
        db.put(1L, f(1L, null, "合同.docx", false, false));
        db.put(2L, f(2L, null, STAGING, true, false));
        db.put(3L, f(3L, null, "多余.docx", false, false));

        var r = svc.applyToDatabase(7L, new TreeManifest(1, List.of(n(1L, null, "合同.docx", false, false))));

        assertFalse(db.get(2L).getIsDeleted(), "缓存区不属于版本内容，退回不许把它送进回收站");
        assertTrue(db.get(3L).getIsDeleted(), "普通文件照旧按清单同步");
        assertEquals(1, r.softDeleted());
    }

    /** 目标版本记着的是一个早已被换掉的旧缓存区：不许把旧的复活成第二个活缓存区，也不许软删现在这个。 */
    @Test
    void staleStagingInManifestDoesNotReviveNorReplaceTheLiveOne() {
        db.put(2L, f(2L, null, STAGING, true, true));   // 旧缓存区，在回收站
        db.put(5L, f(5L, null, STAGING, true, false));  // 现在活着的缓存区

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(n(2L, null, STAGING, true, false))));

        assertFalse(db.get(5L).getIsDeleted(), "活着的缓存区不许被软删");
        assertTrue(db.get(2L).getIsDeleted(), "旧缓存区不许被复活");
        assertEquals(1, liveRootStagingCount());
    }

    /** 清单里的旧缓存区连行都没了：不新建第二个活缓存区，它名下的文件挂到现在这个缓存区下面。 */
    @Test
    void purgedStagingInManifestMapsOntoTheLiveOne() {
        db.put(5L, f(5L, null, STAGING, true, false));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(2L, null, STAGING, true, false),
                n(9L, 2L, "证据.pdf", false, false))));

        assertEquals(1, liveRootStagingCount(), "只能有一个活着的根级缓存区");
        assertEquals(5L, db.get(9L).getParentId(), "旧缓存区名下的文件挂到活着的缓存区下");
        assertFalse(db.get(5L).getIsDeleted());
    }
}
