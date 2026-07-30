package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoServiceTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(props);
    }

    @Test
    void initCreatesRepoWithSeparateGitDirAndWorkTree(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        ProjectRepoService s = svc(root);
        assertFalse(s.isInitialized(7L));

        s.init(7L, "韩泽伟", "hzw@example.com");

        assertTrue(s.isInitialized(7L));
        assertTrue(Files.isDirectory(root.resolve("repos/project-7.git")));
        assertFalse(Files.exists(root.resolve("projects/7/.git")),
                "工作区目录下不得出现 .git");

        try (Repository repo = s.open(7L)) {
            assertEquals(root.resolve("projects/7").toRealPath(),
                    repo.getWorkTree().toPath().toRealPath());
            assertNotNull(repo.resolve("HEAD"), "初始版本应已提交");
        }
    }
}
