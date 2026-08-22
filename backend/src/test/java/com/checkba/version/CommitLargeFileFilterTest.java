package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 尽调模块 P3 稳定性余项 #3（dev-board#100）：commitAll 全量 {@code git add}，没有任何
 * 体积过滤——会议录音的 webm/mp3、手机端回传的现场影像视频、大 pdf 全都会进版本历史
 * （见 ProjectRepoService.MAX_BLOB_SIZE_BYTES 注释与
 * docs/superpowers/specs/2026-08-21-due-diligence-module-proposal.md §3）。
 *
 * <p>本测试用默认阈值 50MB（与 blobAt 读侧体积闸同一保守量级）、51MB 文件触发过滤，
 * 与 {@link ProjectRepoServiceTest#readBlobAtCommitRejectsOversizedFileInsteadOfBufferingItIntoHeap}
 * 的既有文件体积约定一致——那条用例反过来要用
 * {@code setMaxTrackedFileSizeBytesForTest(Long.MAX_VALUE)} 关掉本条新加的写侧过滤，
 * 才能继续验证它自己关心的读侧体积闸。
 */
class CommitLargeFileFilterTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * 超限的新增文件不能进这次提交的树——用 git status 直接核验：commit 之后它仍然是
     * "未跟踪"，而不是被悄悄吞进版本库。commit 说明里必须能读到指纹（路径+体积+sha256），
     * 不许连痕迹都不留（"不静默丢东西"的核心断言）。旧代码（全量 add，无体积过滤）下
     * 这条用例必然失败：大文件会被正常提交，git status 里查不到它是"未跟踪"。
     */
    @Test
    void oversizedNewFileStaysUntrackedButFingerprintIsRecordedInCommitMessage(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/说明.txt"), "占位");
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        byte[] big = new byte[51 * 1024 * 1024]; // 51MB，超过默认阈值 50MB
        Files.write(root.resolve("projects/7/现场影像.mp4"), big);
        Files.writeString(root.resolve("projects/7/说明.txt"), "补充说明"); // 顺带一处正常改动

        String sha = s.commitAll(7L, "补充说明并归档现场影像", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha);

        try (Repository repo = s.open(7L); Git git = new Git(repo)) {
            Status status = git.status().call();
            assertTrue(status.getUntracked().contains("现场影像.mp4"),
                "超限文件应保持未跟踪状态，不能被悄悄 add 进版本库: " + status.getUntracked());
            assertFalse(status.getAdded().contains("现场影像.mp4"));
        }

        String expectedFingerprint = sha256Hex(big);
        try (Repository repo = s.open(7L); RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(repo.resolve(sha));
            String full = commit.getFullMessage();
            assertTrue(full.contains("现场影像.mp4"), "提交说明必须列出被跳过的文件名: " + full);
            assertTrue(full.contains(expectedFingerprint), "提交说明必须带上内容指纹（sha256）: " + full);
            assertTrue(full.contains(String.valueOf(big.length)), "提交说明要说清体积: " + full);
        }

        // 小文件的正常改动不受影响
        byte[] note = s.readBlobAtCommit(7L, sha, "说明.txt");
        assertArrayEquals("补充说明".getBytes(), note);
    }

    /**
     * 已经在库里的大文件不能被这次改动删掉——只影响"新增/新修改"。先在放宽阈值下把一份
     * 大文件正常提交入库（模拟"这个功能上线前就已经在库里的大文件"），再收紧阈值、提交一处
     * 无关的小改动：旧的大文件必须还能从最新提交里原样读出来，不因为体积过滤而从仓库历史
     * 或工作树跟踪中消失。
     */
    @Test
    void preExistingLargeTrackedFileIsNotRemovedByLaterFilteredCommits(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        byte[] big = new byte[3 * 1024 * 1024]; // 3MB
        new java.util.Random(42).nextBytes(big);
        Files.write(root.resolve("projects/7/庭审录音.mp3"), big);
        // 放宽阈值：模拟这份大文件是在体积过滤上线之前、旧的无过滤行为下入库的
        s.setMaxTrackedFileSizeBytesForTest(Long.MAX_VALUE);
        String sha1 = s.commitAll(7L, "上传庭审录音", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha1);
        assertArrayEquals(big, s.readBlobAtCommit(7L, sha1, "庭审录音.mp3"), "入库时应该原样可读");

        // 收紧到默认阈值，录音文件（3MB）本身没有再改动，只改一份无关小文件
        s.setMaxTrackedFileSizeBytesForTest(ProjectRepoService.DEFAULT_MAX_TRACKED_FILE_SIZE_BYTES);
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        String sha2 = s.commitAll(7L, "改到二稿", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha2);

        assertArrayEquals(big, s.readBlobAtCommit(7L, sha2, "庭审录音.mp3"),
            "体积过滤上线之后，已经在库里的旧大文件不能被这次改动删掉");

        try (Repository repo = s.open(7L); Git git = new Git(repo)) {
            Status status = git.status().call();
            assertTrue(status.isClean(), "录音文件没有被新过滤逻辑动过工作区，不该出现在待处理变更里");
        }
    }

    /**
     * 已跟踪大文件真的被改动（体积仍超限）：这次修改不进版本库，但文件本身（旧版本内容）
     * 仍留在仓库里——不因为"改动被跳过"就连整个文件的跟踪状态都丢了。
     *
     * <p>体积特意选 3MB（用测试专用的低阈值 1MB 触发"超限"判定），远低于读侧体积闸
     * MAX_BLOB_SIZE_BYTES（50MB）——避免断言 readBlobAtCommit 时被那道无关的闸挡住，
     * 干扰本用例真正要核验的"写侧过滤是否放过了旧版本内容"。
     */
    @Test
    void modificationToOversizedTrackedFileIsSkippedButFileStaysTracked(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        byte[] v1 = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(v1, (byte) 1);
        Files.write(root.resolve("projects/7/现场影像.mp4"), v1);
        s.setMaxTrackedFileSizeBytesForTest(Long.MAX_VALUE); // 首次入库时不设限，模拟历史遗留大文件
        String sha1 = s.commitAll(7L, "首次归档现场影像", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha1);

        s.setMaxTrackedFileSizeBytesForTest(1024 * 1024); // 收紧到 1MB，3MB 文件从此判定为超限
        byte[] v2 = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(v2, (byte) 2);
        Files.write(root.resolve("projects/7/现场影像.mp4"), v2); // 修改，体积仍超限
        Files.createFile(root.resolve("projects/7/备注.txt"));
        String sha2 = s.commitAll(7L, "补一份备注", "auto", null, "韩泽伟", "hzw@example.com");
        assertNotNull(sha2, "即便大文件的修改被跳过，无关的小改动仍应正常提交");

        assertArrayEquals(v1, s.readBlobAtCommit(7L, sha2, "现场影像.mp4"),
            "超限的修改不能进版本库，最新提交里这个文件应保持旧版本内容（不是被删、也不是新版本）");
    }

    /**
     * 极端情况：这一轮除了一份超限文件之外什么都没变——旧代码在这种情况下会把大文件
     * 整份原样提交（因为它压根不做任何过滤）；新代码必须仍然产生一条提交记录跳过信息，
     * 不能因为"树没有变化"就连指纹记录都不写、让这次"有大文件出现过"的事实彻底无痕迹。
     */
    @Test
    void skipOnlyRoundStillProducesACommitRecordingTheSkip(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/说明.txt"), "占位");
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        Files.write(root.resolve("projects/7/现场影像.mp4"), new byte[51 * 1024 * 1024]);
        String sha = s.commitAll(7L, "现场影像入库", "auto", null, "韩泽伟", "hzw@example.com");

        assertNotNull(sha, "唯一的变化就是一份被跳过的大文件，也必须落一笔可追溯的记录");
        try (Repository repo = s.open(7L); Git git = new Git(repo)) {
            assertTrue(git.status().call().getUntracked().contains("现场影像.mp4"));
        }
        try (Repository repo = s.open(7L); RevWalk rw = new RevWalk(repo)) {
            String full = rw.parseCommit(repo.resolve(sha)).getFullMessage();
            assertTrue(full.contains("现场影像.mp4"), full);
        }
    }
}
