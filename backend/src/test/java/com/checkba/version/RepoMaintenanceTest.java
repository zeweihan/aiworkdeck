package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepoMaintenanceTest {

    @Test
    void gcPreservesEveryReachableVersion(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        s.init(7L, "韩泽伟", "hzw@example.com");

        for (int i = 1; i <= 5; i++) {
            Files.writeString(root.resolve("projects/7/合同.txt"), "第 " + i + " 稿");
            s.commitAll(7L, "改了 " + i, "auto", null, "韩泽伟", "hzw@example.com");
        }

        List<VersionEntry> before = s.log(7L, "HEAD", 100);
        assertEquals(6, before.size());

        s.gc(7L);

        List<VersionEntry> after = s.log(7L, "HEAD", 100);
        assertEquals(before.size(), after.size(), "GC 不得删除任何可达版本");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).sha(), after.get(i).sha(),
                    "GC 前后的版本序列必须逐条相同——历史永不重写");
        }
        // 历史内容仍可取回
        assertEquals("初稿", new String(
                s.readBlobAtCommit(7L, before.get(before.size() - 1).sha(), "合同.txt")));
    }
}
