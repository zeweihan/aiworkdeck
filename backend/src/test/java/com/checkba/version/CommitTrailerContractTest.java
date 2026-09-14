// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：项目 Git 仓库提交说明的三条尾注常量。
 *
 * <p><b>这三个字面量写进了用户产物</b>——用户项目目录下那个真实的 Git 仓库
 * （{@code repos/project-N.git}），每一条提交说明的正文里都带着它们，而且是
 * 用户拿 git 客户端 clone 出去之后仍然读得到的历史。读侧
 * （{@code extractTrailer}，时间线区分「自动存档」与「工作段」、显示备注、
 * 显示体积过滤掉的大文件指纹）也按同一批字面量解析。
 *
 * <p>因此改动任何一个字面量都会破坏兼容：新代码读不出存量仓库里的旧尾注，
 * 时间线会把历史全部退化成无 kind、无备注、无跳过记录。要改必须同时提供迁移
 * 或双读，不能只改常量。本用例锁的就是这一点，故意断言在**字面量**上，
 * 而不是引用生产常量（引用常量的话，改名重构会连测试一起改绿）。
 *
 * <p>命令：{@code cd backend && mvn -Dtest=CommitTrailerContractTest test}
 */
class CommitTrailerContractTest {

    private static final String KIND_TRAILER = "X-AWD-Kind: ";
    private static final String NOTE_TRAILER = "X-AWD-Note: ";
    private static final String SKIPPED_TRAILER = "X-AWD-Skipped-Large-Files: ";
    private static final String RESOLUTIONS_TRAILER = "X-AWD-Resolutions: ";

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
    }

    private String headMessage(ProjectRepoService s, long projectId) throws Exception {
        try (Repository repo = s.open(projectId); RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(repo.resolve("HEAD"));
            return head.getFullMessage();
        }
    }

    /** {@code init} 落的初始提交带 kind 尾注：存量仓库的第一条历史就依赖它。 */
    @Test
    void initialCommitCarriesKindTrailer(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 7L);
        assertTrue(msg.contains(KIND_TRAILER + "session"),
                "初始提交说明必须含 \"" + KIND_TRAILER + "session\"，实际：\n" + msg);
    }

    /** commitAll 的三条尾注一次性锁住：kind、备注、体积过滤跳过清单。 */
    @Test
    void commitAllCarriesKindNoteAndSkippedTrailers(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/9"));
        ProjectRepoService s = svc(root);
        s.init(9L, "韩泽伟", "hzw@example.com");
        // 阈值调到 8 字节，用一个 64 字节的小文件触发体积过滤，不必真造 50MB
        s.setMaxTrackedFileSizeBytesForTest(8L);
        Files.write(root.resolve("projects/9/大附件.bin"), new byte[64]);

        s.commitAll(9L, "第一次存档", "session", "开庭前定稿", "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 9L);
        assertTrue(msg.contains(KIND_TRAILER + "session"),
                "缺 \"" + KIND_TRAILER + "\"，实际：\n" + msg);
        assertTrue(msg.contains(NOTE_TRAILER + "开庭前定稿"),
                "缺 \"" + NOTE_TRAILER + "\"，实际：\n" + msg);
        assertTrue(msg.contains(SKIPPED_TRAILER),
                "缺 \"" + SKIPPED_TRAILER + "\"，实际：\n" + msg);
    }

    /**
     * 第四条尾注：冲突裁决结果（spec 2026-09-14 §2.2）。走一次**真实的裁决合并**——
     * 造一条分叉、撞出冲突、裁决后 commitMergeResolution——而不是只测字符串编解码：
     * 尾注的价值在于「律师事后还能看到那一次到底留了谁的」，只有真的写进提交对象、
     * 再从 log() 读回来才算兑现。
     *
     * <p>冲突文件的名字**故意带 {@code ;} 与 {@code =}**：它们正是尾注的两个分隔符，
     * 不编码就会把一行截成两条假记录。中文文件名原样保留（尾注也是给人读的）。
     */
    @Test
    void mergeResolutionCarriesResolutionsTrailerAndReadsBack(@TempDir Path root) throws Exception {
        Path work = root.resolve("projects/11");
        Files.createDirectories(work);
        String tricky = "合同;甲=乙.txt";
        Files.writeString(work.resolve(tricky), "初稿");
        Files.writeString(work.resolve("附件.txt"), "初稿");
        ProjectRepoService s = svc(root);
        s.init(11L, "韩泽伟", "hzw@example.com");

        // 一条分叉：work/1 与 master 改同两份文件 → 两份都冲突
        s.createBranch(11L, "work/1", "master");
        s.checkoutBranch(11L, "work/1");
        Files.writeString(work.resolve(tricky), "我这边");
        Files.writeString(work.resolve("附件.txt"), "我这边");
        s.commitAll(11L, "我这边的工作", "auto", null, "韩泽伟", "hzw@example.com");
        s.checkoutBranch(11L, "master");
        Files.writeString(work.resolve(tricky), "同事那边");
        Files.writeString(work.resolve("附件.txt"), "同事那边");
        s.commitAll(11L, "同事的工作", "auto", null, "同事", "peer@example.com");

        MergeOutcome outcome = s.mergeNoCommit(11L, "work/1", "撞车的工作",
                "韩泽伟", "hzw@example.com");
        assertTrue(!outcome.success(), "这一步必须真的撞出冲突，否则后面测的不是裁决提交");
        assertTrue(s.repositoryMerging(11L));

        // 律师逐份选完（内容由上层 applyResolution 落盘，这里只关心尾注）
        Files.writeString(work.resolve(tricky), "同事那边");
        Files.writeString(work.resolve("附件.txt"), "我这边");
        String sha = s.commitMergeResolution(11L, "撞车的工作",
                Map.of(tricky, "MAIN", "附件.txt", "DRAFT"), "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 11L);
        assertTrue(msg.contains(RESOLUTIONS_TRAILER),
                "缺 \"" + RESOLUTIONS_TRAILER + "\"，实际：\n" + msg);
        assertTrue(msg.contains("合同%3B甲%3D乙.txt=MAIN"),
                "路径里的 ; 与 = 必须编码，否则这一行会被解析成几条假记录，实际：\n" + msg);

        VersionEntry entry = s.log(11L, sha, 1).get(0);
        List<VersionEntry.Resolution> back = entry.resolutions();
        assertEquals(2, back.size(), "读回来的裁决清单：" + back);
        assertEquals("MAIN", back.stream().filter(r -> r.path().equals(tricky))
                .findFirst().orElseThrow().kept());
        assertEquals("DRAFT", back.stream().filter(r -> r.path().equals("附件.txt"))
                .findFirst().orElseThrow().kept());
    }

    /** 干净合并不带这条尾注：时间线上「这一版做过裁决」不能凭空多出来。 */
    @Test
    void cleanMergeCarriesNoResolutionsTrailer(@TempDir Path root) throws Exception {
        Path work = root.resolve("projects/12");
        Files.createDirectories(work);
        Files.writeString(work.resolve("合同.txt"), "初稿");
        ProjectRepoService s = svc(root);
        s.init(12L, "韩泽伟", "hzw@example.com");
        s.createBranch(12L, "work/1", "master");
        s.checkoutBranch(12L, "work/1");
        Files.writeString(work.resolve("我的.txt"), "我这边");
        s.commitAll(12L, "我这边的工作", "auto", null, "韩泽伟", "hzw@example.com");
        s.checkoutBranch(12L, "master");
        Files.writeString(work.resolve("同事的.txt"), "同事那边");
        s.commitAll(12L, "同事的工作", "auto", null, "同事", "peer@example.com");

        s.mergeNoCommit(12L, "work/1", "两不相干", "韩泽伟", "hzw@example.com");
        String sha = s.commitMergeResolution(12L, "两不相干", Map.of(),
                "韩泽伟", "hzw@example.com");

        assertTrue(!headMessage(s, 12L).contains(RESOLUTIONS_TRAILER));
        assertTrue(s.log(12L, sha, 1).get(0).resolutions().isEmpty());
    }
}
