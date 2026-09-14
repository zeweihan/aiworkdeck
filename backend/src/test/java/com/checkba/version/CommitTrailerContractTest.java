// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.storage.StorageProperties;
import com.checkba.version.merge.Decision;
import com.checkba.version.merge.MergeRecord;
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
    private static final String MERGE_CONTEXT_TRAILER = "X-AWD-Merge-Context: ";
    private static final String MERGES_TRAILER = "X-AWD-Merges: ";

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

    // ==================== 第五、六条尾注：合并语境与逐处裁决（spec 2026-09-14 §4.6） ====================

    /**
     * 造一条真的撞车：{@code work/1} 与 master 都改 {@code files} 里的每一份，
     * 随后 {@code mergeNoCommit} 把仓库停在 MERGING 态，等调用方 {@code commitMergeResolution}。
     * 尾注的价值在于「律师事后还能看到那一次到底怎么合的」，只有真写进提交对象、
     * 再从 {@code log()} 读回来才算兑现，所以这里不测字符串编解码，测真提交。
     */
    private ProjectRepoService conflictedRepo(Path root, long projectId, String... files) throws Exception {
        Path work = root.resolve("projects/" + projectId);
        Files.createDirectories(work);
        for (String f : files) Files.writeString(work.resolve(f), "初稿");
        ProjectRepoService s = svc(root);
        s.init(projectId, "韩泽伟", "hzw@example.com");
        s.createBranch(projectId, "work/1", "master");
        s.checkoutBranch(projectId, "work/1");
        for (String f : files) Files.writeString(work.resolve(f), "我这边");
        s.commitAll(projectId, "我这边的工作", "auto", null, "韩泽伟", "hzw@example.com");
        s.checkoutBranch(projectId, "master");
        for (String f : files) Files.writeString(work.resolve(f), "同事那边");
        s.commitAll(projectId, "同事的工作", "auto", null, "同事", "peer@example.com");
        MergeOutcome outcome = s.mergeNoCommit(projectId, "work/1", "撞车的工作",
                "韩泽伟", "hzw@example.com");
        assertTrue(!outcome.success(), "这一步必须真的撞出冲突，否则后面测的不是裁决提交");
        assertTrue(s.repositoryMerging(projectId));
        return s;
    }

    private static Decision d(String key, String side, String action) {
        return new Decision(key, side, action);
    }

    /**
     * 逐处裁决的清单往返：一份中文名文件与一份名字里带 {@code ; = %} 的文件
     * （三个字符正是这一行的分隔符与转义符，不编码就会把一行截成几条假记录），
     * 清单里既有「留了哪一边」的 A/R，也有没有侧别的 {@code X}（律师自己改的）与
     * {@code F}（另一边只改了格式、没自动合过来）。
     */
    @Test
    void mergesTrailerRoundTripsManualDecisions(@TempDir Path root) throws Exception {
        String tricky = "合同;甲=乙%3.docx";
        ProjectRepoService s = conflictedRepo(root, 21L, "起诉状.docx", tricky);

        List<MergeRecord> merges = List.of(
                new MergeRecord("起诉状.docx", "manual",
                        List.of(d("p3", "M", "A"), d("p7", "T", "A"), d("p12", "M", "A"),
                                d("p12", "T", "R"), d("p9", "", "X"), d("p20", "", "F"),
                                d("t1.2.3", "M", "A")),
                        0, 0),
                new MergeRecord(tricky, "manual", List.of(d("s3", "T", "A")), 0, 0));

        String sha = s.commitMergeResolution(21L, "撞车的工作",
                Map.of("起诉状.docx", "MERGED", tricky, "MERGED"),
                merges, "adopt", "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 21L);
        assertTrue(msg.contains(MERGES_TRAILER), "缺 \"" + MERGES_TRAILER + "\"，实际：\n" + msg);
        assertTrue(msg.contains("起诉状.docx=manual:p3MA,p7TA,p12MA,p12TR,p9X,p20F,t1.2.3MA"),
                "逐处裁决的写法必须逐字对上 spec §4.6 的例子，实际：\n" + msg);
        assertTrue(msg.contains("合同%3B甲%3D乙%253.docx=manual:s3TA"),
                "路径里的 ; = % 必须编码（% 第一个换），否则这一行会被解析成几条假记录，实际：\n" + msg);
        assertTrue(msg.contains(RESOLUTIONS_TRAILER), "MERGED 也要照常写进裁决尾注，实际：\n" + msg);
        assertTrue(msg.contains("=MERGED"), "X-AWD-Resolutions 的值域要容得下 MERGED，实际：\n" + msg);

        VersionEntry entry = s.log(21L, sha, 1).get(0);
        List<VersionEntry.MergeSummary> back = entry.merges();
        assertEquals(2, back.size(), "读回来的合并清单：" + back);
        VersionEntry.MergeSummary one = back.stream()
                .filter(m -> m.path().equals("起诉状.docx")).findFirst().orElseThrow();
        assertEquals("manual", one.mode());
        assertEquals(List.of(d("p3", "M", "A"), d("p7", "T", "A"), d("p12", "M", "A"),
                d("p12", "T", "R"), d("p9", "", "X"), d("p20", "", "F"),
                d("t1.2.3", "M", "A")), one.decisions());
        VersionEntry.MergeSummary two = back.stream()
                .filter(m -> m.path().equals(tricky)).findFirst().orElseThrow();
        assertEquals(List.of(d("s3", "T", "A")), two.decisions(),
                "带 ; = % 的路径必须原样解回来：" + two.path());
        assertEquals("adopt", entry.mergeContext());
    }

    /** 自动合并那一档不逐处记，只记两边各合入几处（{@code M<n>,T<m>}）。 */
    @Test
    void mergesTrailerAutoCounts(@TempDir Path root) throws Exception {
        ProjectRepoService s = conflictedRepo(root, 22L, "合同.docx");

        String sha = s.commitMergeResolution(22L, "取回最新稿",
                Map.of("合同.docx", "MERGED"),
                List.of(new MergeRecord("合同.docx", "auto", List.of(), 3, 4)),
                "cloud", "韩泽伟", "hzw@example.com");

        assertTrue(headMessage(s, 22L).contains("合同.docx=auto:M3,T4"),
                "自动合并写两边各几处，实际：\n" + headMessage(s, 22L));

        VersionEntry entry = s.log(22L, sha, 1).get(0);
        VersionEntry.MergeSummary m = entry.merges().get(0);
        assertEquals("auto", m.mode());
        assertEquals(3, m.mainCount());
        assertEquals(4, m.otherCount());
        assertTrue(m.decisions().isEmpty(), "auto 档不该解出逐处裁决：" + m.decisions());
        assertEquals("cloud", entry.mergeContext());
    }

    /**
     * 一份 500 处以上的长文书：尾注截到 500 条并追加 {@code +N}，
     * 不能把一条提交说明撑成几十 KB（它是每次 {@code git log} 都要读出来的）。
     */
    @Test
    void mergesTrailerTruncatesAt500(@TempDir Path root) throws Exception {
        ProjectRepoService s = conflictedRepo(root, 23L, "长文书.docx");
        List<Decision> many = new java.util.ArrayList<>();
        for (int i = 0; i < 507; i++) many.add(d("p" + i, "M", "A"));

        String sha = s.commitMergeResolution(23L, "撞车的工作",
                Map.of("长文书.docx", "MERGED"),
                List.of(new MergeRecord("长文书.docx", "manual", many, 0, 0)),
                "session-end", "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 23L);
        assertTrue(msg.contains("p499MA,+7"),
                "第 500 条之后要截断并追加 +7，实际：\n" + msg);
        assertTrue(!msg.contains("p500MA"), "第 501 条起不该写进尾注，实际：\n" + msg);

        VersionEntry.MergeSummary m = s.log(23L, sha, 1).get(0).merges().get(0);
        assertEquals(500, m.decisions().size(), "截断后读回 500 条，实际 " + m.decisions().size());
        assertEquals(d("p499", "M", "A"), m.decisions().get(499));
    }

    /**
     * 语境尾注是所有裁决提交都要写的——包括这一版只有整份三选一、一处逐段合并都没有的：
     * 提交历史标签页要靠它把裸的 MAIN/DRAFT 翻成对的话（三语境里 MAIN 指向的物理侧不同，
     * 见 version-control.md 的方向表），没有它「留了你这边」在结束工作撞车语境下是反的。
     */
    @Test
    void mergeContextTrailerWrittenWithWholeFileResolutions(@TempDir Path root) throws Exception {
        ProjectRepoService s = conflictedRepo(root, 24L, "证据目录.pdf");

        String sha = s.commitMergeResolution(24L, "结束工作",
                Map.of("证据目录.pdf", "MAIN"), null, "session-end",
                "韩泽伟", "hzw@example.com");

        String msg = headMessage(s, 24L);
        assertTrue(msg.contains(MERGE_CONTEXT_TRAILER + "session-end"),
                "缺 \"" + MERGE_CONTEXT_TRAILER + "\"，实际：\n" + msg);
        assertTrue(!msg.contains(MERGES_TRAILER),
                "一处逐段合并都没有时不该写合并尾注，实际：\n" + msg);

        VersionEntry entry = s.log(24L, sha, 1).get(0);
        assertEquals("session-end", entry.mergeContext());
        assertTrue(entry.merges().isEmpty());
        assertEquals("MAIN", entry.resolutions().get(0).kept());
    }
}
