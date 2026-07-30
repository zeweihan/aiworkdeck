package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 3 期「保留冲突态的合并原语」——纯 git 层，验证 {@link ProjectRepoService}
 * 的 mergeKeepingConflicts/repositoryMerging/mergeHeadRef/abortMerge/commitMergeResolution。
 * fixture 照抄 {@link ProjectRepoBranchTest#seeded}。
 */
class ConflictMergeTest {

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
    void conflictKeepsMergingStateAndBothTipsIntact(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "draft/1", "HEAD");
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, "draft/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        s.commitAll(7L, "稿", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, s.mainBranch());

        MergeOutcome r = s.mergeKeepingConflicts(7L, "draft/1", "采纳", "A", "a@x");

        assertFalse(r.success());
        assertTrue(r.conflictingPaths().contains("合同.txt"));
        assertTrue(s.repositoryMerging(7L), "必须保留 MERGING 态");
        assertEquals(s.resolveRef(7L, "draft/1"), s.mergeHeadRef(7L));
    }

    @Test
    void abortMergeRestoresCleanMainline(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "draft/1", "HEAD");
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, "draft/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        s.commitAll(7L, "稿", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, s.mainBranch());
        s.mergeKeepingConflicts(7L, "draft/1", "采纳", "A", "a@x");

        s.abortMerge(7L);

        assertFalse(s.repositoryMerging(7L));
        assertEquals("主线改动", Files.readString(root.resolve("projects/7/合同.txt")));
        assertEquals("稿上改动",
                new String(s.readBlobAtCommit(7L, "draft/1", "合同.txt")), "稿分毫无损");
    }

    @Test
    void resolveThenCommitProducesTwoParentSessionNode(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "draft/1", "HEAD");
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, "draft/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        s.commitAll(7L, "稿", "auto", null, "A", "a@x");
        s.checkoutBranch(7L, s.mainBranch());
        s.mergeKeepingConflicts(7L, "draft/1", "采纳", "A", "a@x");

        // 冲突后模拟裁决：直接把选定内容写进工作区
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");
        String sha = s.commitMergeResolution(7L, "采纳：试验稿", "A", "a@x");

        assertNotNull(sha);
        VersionEntry head = s.log(7L, "HEAD", 1).get(0);
        assertEquals(2, head.parents().size(), "裁决提交必须是双亲合并节点");
        assertEquals("session", head.kind());
        assertFalse(s.repositoryMerging(7L));
    }

    /**
     * 无冲突的干净路径：与既有 merge() 完全等价（success、双亲、署名），
     * 断言口径照 {@code ProjectRepoBranchTest.mergeOfTrueThreeWayCreatesMergeCommitWithGivenAuthor}。
     */
    @Test
    void cleanMergeStillCommitsWithAuthor(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "draft/1", "HEAD");

        // 主线改一个文件
        Files.writeString(root.resolve("projects/7/主线.txt"), "主线新增");
        s.commitAll(7L, "主线改了", "auto", null, "系统进程", "system@example.com");

        // 稿件分支改另一个文件——两边可以干净合并，必然产生真正的合并提交
        s.checkoutBranch(7L, "draft/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "稿件改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.mergeKeepingConflicts(7L, "draft/1", "采纳这一稿",
                "韩泽伟", "hzw@example.com");

        assertTrue(r.success());
        assertFalse(r.fastForward());
        assertNotNull(r.mergeSha());
        assertTrue(r.conflictingPaths().isEmpty());
        assertFalse(s.repositoryMerging(7L), "干净路径不应留在 MERGING 态");

        List<VersionEntry> log = s.log(7L, s.mainBranch(), 1);
        assertEquals(1, log.size());
        VersionEntry mergeEntry = log.get(0);
        assertEquals(r.mergeSha(), mergeEntry.sha());
        assertEquals(2, mergeEntry.parents().size(), "合并提交必须有两个父提交");
        assertEquals("韩泽伟", mergeEntry.authorName(),
                "合并提交署名必须是传入的 authorName，不能退化成进程用户");
        assertEquals("session", mergeEntry.kind());
    }
}
