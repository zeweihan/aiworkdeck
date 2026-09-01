package com.checkba.version;

import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRepoHistoryTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
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

    /**
     * 回归测试：根提交（初始版本）没有父提交，形如 sha^ 的 fromRef resolve 不出来。
     * 原实现遇到 resolve 失败就直接返回空列表——但初始版本明明包含文件，
     * VersionNodeDetail 展开根节点时会显示「这一版没有文件改动」，具有误导性。
     * 修复后应与空树比较，让根提交自己的文件以 ADD 呈现。
     */
    @Test
    void diffNameStatusOnRootCommitComparesAgainstEmptyTree(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String sha = s.log(7L, "HEAD", 1).get(0).sha();

        List<FileChange> changes = s.diffNameStatus(7L, sha + "^", sha);

        assertEquals(1, changes.size());
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("合同.txt") && c.type() == FileChange.Type.ADD));
    }

    @Test
    void pendingChangesReturnsEmptyWhenWorkingTreeClean(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertTrue(s.pendingChanges(7L).isEmpty());
    }

    @Test
    void pendingChangesReportsAddModifyAndDelete(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        // 补一个已提交文件，专门用来被磁盘删除——seeded() 只落了 合同.txt。
        Files.writeString(root.resolve("projects/7/待删除.txt"), "占位");
        assertNotNull(s.commitAll(7L, "补一个待删除文件", "auto", null, "韩泽伟", "hzw@example.com"));

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿"); // MODIFY
        Files.writeString(root.resolve("projects/7/新增.txt"), "新文件"); // ADD
        Files.delete(root.resolve("projects/7/待删除.txt")); // DELETE：只从磁盘删，不 git rm

        List<FileChange> changes = s.pendingChanges(7L);

        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("合同.txt") && c.type() == FileChange.Type.MODIFY));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("新增.txt") && c.type() == FileChange.Type.ADD));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("待删除.txt") && c.type() == FileChange.Type.DELETE),
                "磁盘删除但未 git rm 的文件也应被报告为 DELETE——JGit 的 getRemoved()/getMissing() 语义不同，"
                        + "getMissing() 才是「工作区缺失但索引仍有」的那批");
        assertEquals(3, changes.size());
    }

    @Test
    void logForPathReturnsOnlyCommitsTouchingThatFile(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/别的.txt"), "x");
        s.commitAll(7L, "改了别的", "auto", null, "韩泽伟", "hzw@example.com");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了合同", "auto", null, "韩泽伟", "hzw@example.com");

        List<VersionEntry> all = s.log(7L, "HEAD", 100);
        List<VersionEntry> only = s.logForPath(7L, "HEAD", "合同.txt", 100);

        assertTrue(only.size() < all.size());
        assertTrue(only.stream().anyMatch(v -> v.message().contains("合同")));
        assertTrue(only.stream().noneMatch(v -> v.message().contains("别的")));
    }

    /**
     * 回归测试：单文件历史必须保留命名的工作段节点。
     *
     * JGit 的 addPath（git 默认历史简化）会把「相对第一父提交 TREESAME」的合并提交
     * 整条剪掉——而「结束本次工作」用的正是 NO_FF 合并，主线在工作期间不动时，合并
     * 提交的树跟工作分支那一父完全相同，天然 TREESAME。结果是律师命名的工作段节点
     * 在单文件视图里全部消失，只剩自动存档。这里造一个真实的 NO_FF 合并段来守。
     */
    @Test
    void logForPathKeepsNamedWorkSessionMergeNodes(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);

        s.createBranch(7L, "work/1", "HEAD");
        s.checkoutBranch(7L, "work/1");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        assertNotNull(s.commitAll(7L, "修改了《合同》", "auto", null, "韩泽伟", "hzw@example.com"));
        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome outcome = s.merge(7L, "work/1", "发客户第一稿", "韩泽伟", "hzw@example.com");
        assertTrue(outcome.success());
        assertNotNull(outcome.mergeSha(), "前提：NO_FF 合并必须产生一个真实的合并提交");

        List<VersionEntry> only = s.logForPath(7L, "HEAD", "合同.txt", 100);

        assertTrue(only.stream().anyMatch(v -> outcome.mergeSha().equals(v.sha())),
                "命名的工作段合并节点必须出现在单文件历史里，实际拿到: "
                        + only.stream().map(VersionEntry::message).toList());
        assertTrue(only.stream().anyMatch(v -> "session".equals(v.kind())),
                "单文件历史里应该还有 kind=session 的工作段节点");
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

    // ==================== 时间线署名的读时本地化（dev-board#351）====================
    // 单机模式的提交作者名就是库里那个中文哨兵「本机用户」，它随提交写进了 Git 历史。
    // 历史永不重写，作者名还派生了提交邮箱，所以只能在**读出来交给 UI 时**按界面语言换，
    // Git 对象一字节都不许动——下面两条用同一个仓库分别以中/英文读一遍来钉死这一点。

    @AfterEach
    void resetLangText() {
        LangText.reset();
    }

    private void useEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
    }

    @Test
    void localUserAuthorIsLocalizedOnReadWithoutRewritingHistory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        ProjectRepoService s = svc(root);
        // 提交侧照旧写哨兵值：写入语义不动，历史里永远是同一个署名
        s.init(7L, LocalIdentityService.LOCAL_DISPLAY_NAME, "本机用户@aiworkdeck.local");

        LangText.reset();
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, s.log(7L, "HEAD", 1).get(0).authorName(),
                "中文界面下时间线署名应保持哨兵原值");

        useEnglish();
        assertEquals("Local user", s.log(7L, "HEAD", 1).get(0).authorName(),
                "英文界面下时间线署名应本地化");

        LangText.reset();
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, s.log(7L, "HEAD", 1).get(0).authorName(),
                "切回中文又变了 = 提交对象被改写了，本地化必须只作用于出参");
    }

    @Test
    void realAuthorNameIsNeverTouchedEvenInEnglishUi(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);   // 作者是真实姓名「韩泽伟」
        useEnglish();
        assertEquals("韩泽伟", s.log(7L, "HEAD", 1).get(0).authorName(),
                "云端协作方的真实署名在英文界面下也一个字都不能动");
    }
}
