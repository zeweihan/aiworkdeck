package com.checkba.version;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JGit `add(".")` 在真实用户目录上的收录行为探针。
 *
 * 存在理由：把「项目 = Git 仓库」从 opt-in 改成默认开启之后，建仓会发生在律师自选的任意文件夹上
 * （Project.localRoot）。而 ProjectRepoService.init 用的是无差别的 `git add "."`，
 * 数据库导入侧那套 3000 条上限 / 20 层深度 / 跳过点开头目录的规则（LocalProjectService）
 * 对它一概不生效。
 *
 * 本测试钉死三条决定「默认建仓会不会把用户整个文件夹原样吞进对象库」的行为：
 *   1. 工作区自带的 .gitignore 是否被应用
 *   2. 嵌套的 .git 目录是当 gitlink 跳过，还是展开其内容
 *   3. 符号链接是存成 symlink blob，还是跟进目标把目标内容收进来
 *
 * 调用序列与 ProjectRepoService.init 完全一致（repo.create(true) + add(".") + commit），
 * gitDir 与 workTree 分离也照搬，保证结论对生产路径成立。
 */
class JGitAddBehaviorProbeTest {

    /** 把某次提交的完整树摊平成 path -> FileMode，含子目录递归。 */
    private static Map<String, FileMode> flattenTree(Repository repo, ObjectId commitId) throws Exception {
        Map<String, FileMode> out = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(false);
                while (tw.next()) {
                    out.put(tw.getPathString(), tw.getFileMode(0));
                    // gitlink（嵌套仓库）不可进入；普通目录才递归
                    if (tw.isSubtree() && tw.getFileMode(0) != FileMode.GITLINK) {
                        tw.enterSubtree();
                    }
                }
            }
        }
        return out;
    }

    @Test
    void probeAddBehaviorOnARealisticUserFolder(@TempDir Path tmp) throws Exception {
        Path workTree = tmp.resolve("lawyer-folder");
        Path gitDir = tmp.resolve("repos").resolve("project-1.git");
        Path outside = tmp.resolve("outside-target");   // 工作区之外，符号链接的目标
        Files.createDirectories(workTree);
        Files.createDirectories(gitDir.getParent());
        Files.createDirectories(outside);

        // --- 1. 普通文件（基准） ---
        Files.writeString(workTree.resolve("股权转让协议.docx"), "content");

        // --- 2. 用户自带的 .gitignore ---
        Files.writeString(workTree.resolve(".gitignore"), "node_modules/\n*.log\n");
        Files.writeString(workTree.resolve("app.log"), "should be ignored by *.log");
        Path nodeModules = workTree.resolve("node_modules").resolve("pkg");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("index.js"), "should be ignored by node_modules/");

        // --- 3. 点开头目录里的普通文件（数据库导入会跳过，git 呢？） ---
        Path hidden = workTree.resolve(".venv").resolve("lib");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("site.py"), "hidden dir, plain file");
        Files.writeString(workTree.resolve(".DS_Store"), "mac junk");

        // --- 4. 嵌套的 git 仓库（律师文件夹里恰好有个别人的项目） ---
        Path nested = workTree.resolve("nested-repo");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("inner.txt"), "inside a nested repo");
        try (Git ng = Git.init().setDirectory(nested.toFile()).call()) {
            ng.add().addFilepattern(".").call();
            ng.commit().setMessage("nested init").setAuthor("t", "t@t").call();
        }

        // --- 5. 指向工作区外部的符号链接（目录 + 文件各一条） ---
        Files.writeString(outside.resolve("big.bin"), "x".repeat(1024));
        boolean symlinkSupported;
        try {
            Files.createSymbolicLink(workTree.resolve("link-to-dir"), outside);
            Files.createSymbolicLink(workTree.resolve("link-to-file"), outside.resolve("big.bin"));
            symlinkSupported = true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            symlinkSupported = false;
        }

        // ===== 完全照搬 ProjectRepoService.init 的调用序列 =====
        Map<String, FileMode> tree;
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(workTree.toFile())
                .build()) {
            repo.create(true);
            try (Git git = new Git(repo)) {
                git.add().addFilepattern(".").call();
                RevCommit c = git.commit()
                        .setMessage("初始版本\n\nX-AWD-Kind: session")
                        .setAuthor("AI Workdeck", "ai@aiworkdeck.local")
                        .setAllowEmpty(true)
                        .call();
                tree = flattenTree(repo, c.getId());
            }
        }

        List<String> report = new ArrayList<>();
        report.add("=== JGit " + org.eclipse.jgit.internal.JGitText.class.getPackage().getImplementationVersion()
                + " add(\".\") 收录结果（" + tree.size() + " 条） ===");
        tree.forEach((p, m) -> report.add(String.format("  %-30s %s", p, modeName(m))));
        report.add("symlink 可创建: " + symlinkSupported);
        System.out.println(String.join("\n", report));

        // ---------- 结论 1：.gitignore 是否生效 ----------
        boolean gitignoreApplied = !tree.containsKey("app.log") && !tree.containsKey("node_modules/pkg/index.js");
        System.out.println("[结论1] 工作区自带 .gitignore 被应用 = " + gitignoreApplied
                + "  (app.log 在树里=" + tree.containsKey("app.log")
                + ", node_modules/pkg/index.js 在树里=" + tree.containsKey("node_modules/pkg/index.js") + ")");

        // ---------- 结论 2：嵌套 .git ----------
        FileMode nestedMode = tree.get("nested-repo");
        boolean nestedIsGitlink = nestedMode == FileMode.GITLINK;
        boolean nestedExpanded = tree.containsKey("nested-repo/inner.txt");
        boolean nestedDotGitLeaked = tree.keySet().stream().anyMatch(p -> p.startsWith("nested-repo/.git"));
        System.out.println("[结论2] 嵌套仓库 mode=" + modeName(nestedMode)
                + "  gitlink=" + nestedIsGitlink + "  内容被展开=" + nestedExpanded
                + "  .git 内部泄漏=" + nestedDotGitLeaked);

        // ---------- 结论 3：符号链接 ----------
        FileMode linkDirMode = tree.get("link-to-dir");
        FileMode linkFileMode = tree.get("link-to-file");
        boolean followedDir = tree.keySet().stream().anyMatch(p -> p.startsWith("link-to-dir/"));
        System.out.println("[结论3] link-to-dir mode=" + modeName(linkDirMode)
                + "  link-to-file mode=" + modeName(linkFileMode)
                + "  目录链接被跟进=" + followedDir);

        // ---------- 结论 4：点开头目录（对照 LocalProjectService 的导入规则） ----------
        boolean hiddenDirCollected = tree.containsKey(".venv/lib/site.py");
        System.out.println("[结论4] 点开头目录里的普通文件被 git 收下 = " + hiddenDirCollected
                + "  (.DS_Store 在树里=" + tree.containsKey(".DS_Store") + ")");

        // ===== 断言：钉死行为，任何一条变了都要重新论证默认建仓的闸门设计 =====
        assertTrue(tree.containsKey("股权转让协议.docx"), "基准：普通文件必须被收录");

        assertTrue(gitignoreApplied,
                "JGit 应当应用工作区自带的 .gitignore —— 若此断言失败，默认建仓的 .gitignore 闸门整个失效，"
                        + "必须改用显式路径集合替代 addFilepattern(\".\")");

        assertSame(FileMode.GITLINK, nestedMode,
                "嵌套 .git 应当被识别为 gitlink 而非展开");
        assertFalse(nestedExpanded, "嵌套仓库的内容不应被展开进本仓");
        assertFalse(nestedDotGitLeaked, "嵌套仓库的 .git 内部文件绝不应进入对象库");

        if (symlinkSupported) {
            assertSame(FileMode.SYMLINK, linkFileMode, "指向文件的符号链接应存成 symlink blob");
            assertSame(FileMode.SYMLINK, linkDirMode, "指向目录的符号链接应存成 symlink blob，不跟进目标");
            assertFalse(followedDir, "符号链接指向的目录内容不应被收进对象库");
        }

        assertTrue(hiddenDirCollected,
                "点开头目录里的普通文件会被 git 收下（数据库导入侧会跳过）—— 这正是「文件树看不见但已被版本记录收录」"
                        + "的来源。若此断言失败说明 JGit 行为变了，spec 的闸门 2 论证需要重写");
    }

    /**
     * 排除规则能不能放进 gitDir 的 info/exclude，而不是往用户文件夹里写 .gitignore。
     *
     * 这条决定默认建仓要不要在律师自己的文件夹里留下一个他没写过的 .gitignore：
     * gitDir 恒在 {globalRoot}/repos/project-{id}.git（工作区之外），
     * 若 JGit 认 $GIT_DIR/info/exclude，就能做到零文件写进用户目录，
     * 而且不会与用户自带的 .gitignore 互相覆盖。
     */
    @Test
    void probeInfoExcludeInsteadOfWritingGitignoreIntoUserFolder(@TempDir Path tmp) throws Exception {
        Path workTree = tmp.resolve("lawyer-folder");
        Path gitDir = tmp.resolve("repos").resolve("project-2.git");
        Files.createDirectories(workTree);
        Files.createDirectories(gitDir.getParent());

        Files.writeString(workTree.resolve("尽调报告.docx"), "keep me");
        Path nm = workTree.resolve("node_modules").resolve("pkg");
        Files.createDirectories(nm);
        Files.writeString(nm.resolve("index.js"), "drop me");
        Path venv = workTree.resolve(".venv").resolve("lib");
        Files.createDirectories(venv);
        Files.writeString(venv.resolve("site.py"), "drop me too");
        // 用户自带的 .gitignore，规则与我们的互不相同 —— 验证两者叠加而非互相覆盖
        Files.writeString(workTree.resolve(".gitignore"), "*.tmp\n");
        Files.writeString(workTree.resolve("draft.tmp"), "user's own rule should still work");

        Map<String, FileMode> tree;
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(workTree.toFile())
                .build()) {
            repo.create(true);

            // 关键动作：排除规则写进 gitDir，不碰用户文件夹
            Path info = gitDir.resolve("info");
            Files.createDirectories(info);
            Files.writeString(info.resolve("exclude"), String.join("\n",
                    "node_modules/", ".venv/", ".*/", "*.mp4", "") );

            try (Git git = new Git(repo)) {
                git.add().addFilepattern(".").call();
                RevCommit c = git.commit()
                        .setMessage("初始版本\n\nX-AWD-Kind: session")
                        .setAuthor("AI Workdeck", "ai@aiworkdeck.local")
                        .setAllowEmpty(true)
                        .call();
                tree = flattenTree(repo, c.getId());
            }
        }

        System.out.println("=== info/exclude 生效验证（" + tree.size() + " 条） ===");
        tree.forEach((p, m) -> System.out.printf("  %-30s %s%n", p, modeName(m)));
        boolean ourRulesWork = !tree.containsKey("node_modules/pkg/index.js")
                && !tree.containsKey(".venv/lib/site.py");
        boolean userRuleStillWorks = !tree.containsKey("draft.tmp");
        System.out.println("[结论5] gitDir/info/exclude 生效 = " + ourRulesWork
                + "  用户自带 .gitignore 仍生效 = " + userRuleStillWorks
                + "  用户文件夹里有没有被我们写入 .gitignore = "
                + (Files.readString(workTree.resolve(".gitignore")).equals("*.tmp\n") ? "没有（原样未动）" : "有（被改写）"));

        assertTrue(tree.containsKey("尽调报告.docx"), "基准：普通文件必须被收录");
        assertTrue(ourRulesWork,
                "JGit 应当读取 $GIT_DIR/info/exclude —— 若失败，排除规则只能写成用户文件夹里的 .gitignore");
        assertTrue(userRuleStillWorks, "用户自带的 .gitignore 规则必须与 info/exclude 叠加生效，而不是被顶掉");
        assertEquals("*.tmp\n", Files.readString(workTree.resolve(".gitignore")),
                "我们不应该改写用户自己的 .gitignore");
    }

    private static String modeName(FileMode m) {
        if (m == null) return "<不在树里>";
        if (m == FileMode.GITLINK) return "GITLINK(160000)";
        if (m == FileMode.SYMLINK) return "SYMLINK(120000)";
        if (m == FileMode.TREE) return "TREE(040000)";
        if (m == FileMode.REGULAR_FILE) return "FILE(100644)";
        if (m == FileMode.EXECUTABLE_FILE) return "EXEC(100755)";
        return m.toString();
    }
}
