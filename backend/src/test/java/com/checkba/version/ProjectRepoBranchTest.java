package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoBranchTest {

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(props);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    @Test
    void createCheckoutAndListBranches(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertEquals(s.mainBranch(), s.currentBranch(7L));

        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");

        assertEquals("work/1001", s.currentBranch(7L));
        assertTrue(s.listBranches(7L).contains("work/1001"));
    }

    /**
     * 单人场景下这是最常见的合并形态：工作期间主线完全没动，本可以快进。
     * 但快进只挪 ref、不产生提交，会把调用方传入的标题和 kind=session 尾注
     * 丢在半路——时间线上就出不来这个工作段的命名节点（这正是 task-15 发现的
     * Bug A）。合并必须显式禁用快进，永远产生一个带标题、kind=session、
     * 双亲提交的合并提交，工作段才有自己的节点。
     */
    @Test
    void mergeIsFastForwardWhenMainUntouched(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.merge(7L, "work/1001", "7 月 28 日下午的工作",
                "韩泽伟", "hzw@example.com");

        assertTrue(r.success());
        assertFalse(r.fastForward(), "即使可以快进也必须被禁用，否则工作段的命名节点会丢失");
        assertTrue(r.conflictingPaths().isEmpty());
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")));

        List<VersionEntry> log = s.log(7L, s.mainBranch(), 1);
        assertEquals(1, log.size());
        VersionEntry head = log.get(0);
        assertEquals(r.mergeSha(), head.sha());
        assertEquals("7 月 28 日下午的工作", head.message(),
                "禁用快进后，合并提交的 message 必须是调用方传入的标题");
        assertEquals("session", head.kind(), "工作段合并提交的 kind 必须是 session");
        assertEquals(2, head.parents().size(), "工作段合并提交必须有两个父提交");
    }

    @Test
    void mergeReportsConflictAndLeavesBothSidesIntact(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");

        // 主线先动
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线改了", "auto", null, "韩泽伟", "hzw@example.com");

        // 稿件在旧起点上改同一文件
        s.checkoutBranch(7L, "work/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿件改动");
        s.commitAll(7L, "稿件改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.merge(7L, "work/1001", "采纳", "韩泽伟", "hzw@example.com");

        assertFalse(r.success());
        assertTrue(r.conflictingPaths().contains("合同.txt"));
        // 合并失败后主线内容不得被破坏
        assertEquals("主线改动", Files.readString(root.resolve("projects/7/合同.txt")));
        // 稿件分支自己的内容也不得被破坏（两边都完好，而不只是主线）
        assertEquals("稿件改动",
                new String(s.readBlobAtCommit(7L, "work/1001", "合同.txt")));
    }

    @Test
    void mergeOfTrueThreeWayCreatesMergeCommitWithGivenAuthor(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");

        // 主线改一个文件
        Files.writeString(root.resolve("projects/7/主线.txt"), "主线新增");
        s.commitAll(7L, "主线改了", "auto", null, "系统进程", "system@example.com");

        // 稿件分支改另一个文件——两边可以干净合并，必然产生真正的合并提交
        s.checkoutBranch(7L, "work/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "稿件改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.merge(7L, "work/1001", "采纳这一稿", "韩泽伟", "hzw@example.com");

        assertTrue(r.success());
        assertFalse(r.fastForward());
        assertNotNull(r.mergeSha());

        List<VersionEntry> log = s.log(7L, s.mainBranch(), 1);
        assertEquals(1, log.size());
        VersionEntry mergeEntry = log.get(0);
        assertEquals(r.mergeSha(), mergeEntry.sha());
        assertEquals(2, mergeEntry.parents().size(), "合并提交必须有两个父提交");
        assertEquals("韩泽伟", mergeEntry.authorName(),
                "合并提交署名必须是传入的 authorName，不能退化成进程用户");
        assertEquals("session", mergeEntry.kind());
    }

    @Test
    void deleteBranchRemovesUnmergedWork(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "废弃改动");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        s.deleteBranch(7L, "work/1001", true);

        assertFalse(s.listBranches(7L).contains("work/1001"));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
}
