package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TreeManifestCaptureTest {

    private ProjectFile file(Long id, Long parentId, String name, boolean folder,
                             String path, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id); f.setProjectId(7L); f.setParentId(parentId); f.setName(name);
        f.setIsFolder(folder); f.setFileType(folder ? null : "docx");
        f.setSortOrder(0); f.setFilePath(path); f.setIsDeleted(deleted);
        return f;
    }

    @Test
    void captureIncludesDeletedNodesAndRoundTripsThroughDisk(@TempDir Path root) throws Exception {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectId(7L)).thenReturn(List.of(
                file(1L, null, "重要协议", true, null, false),
                file(2L, 1L, "股权转让协议.docx", false, "projects/7/重要协议/股权转让协议.docx", false),
                file(3L, null, "废弃.docx", false, "projects/7/废弃.docx", true)
        ));

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService repoSvc = new ProjectRepoService(props);
        ProjectTreeManifestService svc =
                new ProjectTreeManifestService(repo, repoSvc, new ObjectMapper());

        TreeManifest m = svc.capture(7L);
        assertEquals(TreeManifest.CURRENT_VERSION, m.version());
        assertEquals(3, m.nodes().size(), "软删除的节点也必须在清单里");
        assertTrue(m.nodes().stream().anyMatch(n -> n.id() == 3L && n.isDeleted()));

        Files.createDirectories(root.resolve("projects/7"));
        svc.writeToWorkTree(7L, m);

        Path onDisk = root.resolve("projects/7/.awd/tree.json");
        assertTrue(Files.exists(onDisk));

        TreeManifest back = new ObjectMapper().readValue(
                Files.readString(onDisk), TreeManifest.class);
        assertEquals(m, back, "序列化→反序列化必须恒等");
    }
}
