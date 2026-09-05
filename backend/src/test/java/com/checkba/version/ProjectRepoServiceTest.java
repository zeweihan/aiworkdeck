package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoServiceTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
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

    /**
     * blobAt 原来把整份文件字节读进 ByteArrayOutputStream 没有任何体积闸；
     * workTree 就是项目存储根目录、commitAll 全量 add，会议录音/手机端现场影像视频
     * 这类大文件天然会进版本历史，几个并发的历史版本读取请求就能把云端团队服务器
     * 那台只有 1.5GB 堆上限的机器（deploy/cloud/aiworkdeck-cloud.service 的
     * -Xmx1536m）打爆。这里断言超限文件被拒绝而不是被读进堆，小文件不受影响。
     *
     * <p>commitAll 自己也有一道体积闸了（尽调 P3#3，CommitLargeFileFilterTest），
     * 默认阈值同为 50MB——这里关掉它（放宽到 Long.MAX_VALUE），本用例要验证的是
     * **读侧**的闸，不该被写侧的闸挡在提交这一步之前，两条闸各自有独立的测试覆盖。
     */
    @Test
    void readBlobAtCommitRejectsOversizedFileInsteadOfBufferingItIntoHeap(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/说明.txt"), "占位");

        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");
        s.setMaxTrackedFileSizeBytesForTest(Long.MAX_VALUE);

        // 51MB，超过体积闸（50MB）——量级对应会议录音/手机端现场影像视频这类文件
        Files.write(root.resolve("projects/7/现场影像.mp4"), new byte[51 * 1024 * 1024]);
        String sha = s.commitAll(7L, "上传现场影像", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha);

        VersionException e = assertThrows(VersionException.class,
                () -> s.readBlobAtCommit(7L, sha, "现场影像.mp4"));
        assertTrue(e.isUserFacing(), "超限应该是用户可见的错误，不是把内部技术异常糊给律师看");
        assertTrue(e.getMessage().contains("MB"), "错误信息要说清是体积超限: " + e.getMessage());

        // 小文件不受影响，体积闸不能误伤正常文档
        byte[] small = s.readBlobAtCommit(7L, sha, "说明.txt");
        assertArrayEquals("占位".getBytes(), small);
    }

    /**
     * 崩溃/被强杀会在 .git/index.lock 留下残留，JGit 的 DirCache 撞上它必抛
     * LockFailedException；进程内的可重入锁（WorkSessionService.repoLock）对磁盘上的
     * 残留文件毫无作用——不清理的话，自动存档会从崩溃那一刻起永久静默失败，
     * 直到有人手工去项目仓库里删这个文件。用 setLastModifiedTime 把 mtime 回拨到
     * 陈旧阈值之外模拟"这把锁已经放了很久"，完全确定性，不需要真的杀进程。
     */
    @Test
    void commitAllRecoversFromStaleIndexLock(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        Path lockFile = s.gitDir(7L).resolve("index.lock");
        Files.createFile(lockFile);
        Files.setLastModifiedTime(lockFile,
                FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        String sha = s.commitAll(7L, "改到二稿", "auto", null, "韩泽伟", "hzw@example.com");

        assertNotNull(sha, "陈旧锁应被识别并清理，提交应该正常成功而不是抛 VersionException");
        assertFalse(Files.exists(lockFile), "陈旧锁清理后不该再留在磁盘上");
    }

    /**
     * Word/WPS 打开 .docx 会在同目录落一个 `~$合同.docx` 锁文件（dev-board#463）。
     * 它进版本历史的后果不只是噪声：一次「退回」会把这份陈旧锁文件还原回律师的
     * 文件夹，Word 可能据此认为文档「正被他人占用」。排除规则写在
     * $GIT_DIR/info/exclude（gitDir 在工作区之外），不往律师文件夹里写 .gitignore。
     */
    @Test
    void officeLockFilesNeverEnterTheVersionHistory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.docx"), "正文");
        Files.writeString(root.resolve("projects/7/~$合同.docx"), "word lock");

        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        java.util.List<String> paths = s.listPaths(7L, "HEAD");
        assertTrue(paths.contains("合同.docx"), "基准：普通文档必须被收录: " + paths);
        assertFalse(paths.contains("~$合同.docx"), "Office 锁文件不得进初始版本: " + paths);
        assertFalse(Files.exists(root.resolve("projects/7/.gitignore")),
                "排除规则不得写进律师自己的文件夹");

        // 增量路径：init 之后新出现的锁文件同样不该触发一笔提交
        Files.writeString(root.resolve("projects/7/~$备忘录.docx"), "word lock 2");
        assertNull(s.commitAll(7L, "自动存档", "auto", null, "韩泽伟", "hzw@example.com"),
                "只有 Office 锁文件变化时不该产生新版本");
    }

    /**
     * 排除规则必须对**已经存在**的仓库也生效，不能只在 init 那一条路上写。
     * 建仓有三条入口（init / initEmptyForReceive / cloneFromRemote），而
     * prepare-remote 还会删掉整个 gitDir 重建；一切读写又都汇进 open()，
     * 所以 open() 是唯一能覆盖全部情况的落点。幂等：反复打开只留一行。
     */
    @Test
    void excludeRuleIsEnsuredWhenOpeningAPreExistingRepository(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.docx"), "正文");

        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        Path exclude = s.gitDir(7L).resolve("info").resolve("exclude");
        assertTrue(Files.readString(exclude).contains("~$*"), "init 后应有排除规则");

        // 模拟本次修复之前建出来的老仓库：规则不存在
        Files.writeString(exclude, "# 用户自己加的\n*.bak\n");
        try (Repository repo = s.open(7L)) {
            assertNotNull(repo);
        }
        String restored = Files.readString(exclude);
        assertTrue(restored.contains("~$*"), "打开既有仓库时应补上排除规则: " + restored);
        assertTrue(restored.contains("*.bak"), "不得覆盖已有的排除规则: " + restored);

        try (Repository repo = s.open(7L)) {
            assertNotNull(repo);
        }
        long lines = Files.readString(exclude).lines().filter(l -> l.trim().equals("~$*")).count();
        assertEquals(1, lines, "幂等：反复打开只留一行");

        // 老仓库补上规则后，锁文件同样不再进历史
        Files.writeString(root.resolve("projects/7/~$合同.docx"), "word lock");
        assertNull(s.commitAll(7L, "自动存档", "auto", null, "韩泽伟", "hzw@example.com"),
                "补上规则后 Office 锁文件不该产生新版本");
    }
}
