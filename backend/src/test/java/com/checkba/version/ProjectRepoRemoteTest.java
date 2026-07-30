package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoRemoteTest {

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(props);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    private String bareRemote(Path dir) throws Exception {
        Git.init().setBare(true).setDirectory(dir.toFile())
                .setInitialBranch("master").call().close();
        return dir.toUri().toString();
    }

    @Test
    void pushThenFetchRoundTrip(@TempDir Path root, @TempDir Path remote) throws Exception {
        ProjectRepoService s = seeded(root);
        s.setRemoteOrigin(7L, bareRemote(remote));

        ProjectRepoService.PushOutcome out = s.pushMainlineToOrigin(7L, "u", "t");
        assertTrue(out.pushed());
        assertFalse(out.rejected());

        String remoteSha = s.fetchFromOrigin(7L, "u", "t");
        assertEquals(s.resolveRef(7L, "master"), remoteSha);
        assertEquals(remoteSha, s.originMasterSha(7L));
    }

    @Test
    void divergedPushIsRejectedNotThrown(@TempDir Path root, @TempDir Path remote,
                                         @TempDir Path other) throws Exception {
        ProjectRepoService s = seeded(root);
        String url = bareRemote(remote);
        s.setRemoteOrigin(7L, url);
        assertTrue(s.pushMainlineToOrigin(7L, "u", "t").pushed());

        // 第二个"同事"仓库把远端 master 推进一步
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(other.toFile()).call()) {
            Files.writeString(other.resolve("合同.txt"), "同事的第二稿");
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            peer.push().call();
        }

        // 本地也前进一步 → 推送分叉，应被拒而非抛异常
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        s.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        ProjectRepoService.PushOutcome out = s.pushMainlineToOrigin(7L, "u", "t");
        assertFalse(out.pushed());
        assertTrue(out.rejected());

        // fetch 后 isAncestor 能判「主线被别人推进」
        String remoteSha = s.fetchFromOrigin(7L, "u", "t");
        assertNotNull(remoteSha);
        assertFalse(s.isAncestor(7L, remoteSha, "master"));
        assertTrue(s.isAncestor(7L, "master^", "master"));
    }

    @Test
    void milestoneTagsTravelWithPush(@TempDir Path root, @TempDir Path remote) throws Exception {
        ProjectRepoService s = seeded(root);
        s.setRemoteOrigin(7L, bareRemote(remote));
        String sha = s.resolveRef(7L, "master");
        s.tagMilestone(7L, sha, "定稿");
        assertTrue(s.pushMainlineToOrigin(7L, "u", "t").pushed());

        try (Git remoteGit = Git.open(Path.of(java.net.URI.create(
                s.remoteOriginUrl(7L))).toFile())) {
            assertFalse(remoteGit.tagList().call().isEmpty());
        }
    }
}
