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
}
