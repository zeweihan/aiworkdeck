package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoHistoryTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(props);
    }

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    @Test
    void commitAllReturnsNullWhenNothingChanged(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertNull(s.commitAll(7L, "无变更", "auto", null, "韩泽伟", "hzw@example.com"));
    }

    @Test
    void commitAllRecordsKindAndNote(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");

        String sha = s.commitAll(7L, "修改了《合同》", "session", "发客户第一稿",
                "韩泽伟", "hzw@example.com");
        assertNotNull(sha);

        List<VersionEntry> log = s.log(7L, "HEAD", 10);
        assertEquals(2, log.size());
        VersionEntry head = log.get(0);
        assertEquals("修改了《合同》", head.message());
        assertEquals("session", head.kind());
        assertEquals("发客户第一稿", head.note());
        assertEquals("韩泽伟", head.authorName());
    }

    @Test
    void diffNameStatusClassifiesChanges(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        Files.writeString(root.resolve("projects/7/新增.txt"), "新文件");
        String sha = s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        List<FileChange> changes = s.diffNameStatus(7L, sha + "^", sha);
        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("合同.txt") && c.type() == FileChange.Type.MODIFY));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("新增.txt") && c.type() == FileChange.Type.ADD));
    }

    @Test
    void readBlobAtCommitReturnsHistoricBytes(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String first = s.log(7L, "HEAD", 1).get(0).sha();

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        byte[] old = s.readBlobAtCommit(7L, first, "合同.txt");
        assertEquals("初稿", new String(old, StandardCharsets.UTF_8));
        assertNull(s.readBlobAtCommit(7L, first, "不存在.txt"));
    }
}
