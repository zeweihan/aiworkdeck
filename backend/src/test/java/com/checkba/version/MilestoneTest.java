package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneTest {

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    @Test
    void tagThenLogCarriesMilestoneName(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String sha = s.log(7L, "HEAD", 1).get(0).sha();

        s.tagMilestone(7L, sha, "发客户第一稿");

        VersionEntry head = s.log(7L, "HEAD", 1).get(0);
        assertEquals("发客户第一稿", head.milestone());
        assertEquals("发客户第一稿", s.listMilestones(7L).get(sha));
    }

    @Test
    void retagSameShaOverwrites(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String sha = s.log(7L, "HEAD", 1).get(0).sha();
        s.tagMilestone(7L, sha, "旧名");
        s.tagMilestone(7L, sha, "新名");
        assertEquals("新名", s.log(7L, "HEAD", 1).get(0).milestone());
    }

    @Test
    void untaggedVersionHasNullMilestone(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertNull(s.log(7L, "HEAD", 1).get(0).milestone());
    }
}
