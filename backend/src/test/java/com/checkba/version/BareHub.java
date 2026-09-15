// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;

import java.nio.file.Path;

/**
 * 测试用 file:// 裸仓库（模拟云端 / 记忆 hub）的唯一建法。
 *
 * 必须关掉 {@code receive.autogc}：往 file:// 远端 push 时 JGit 在本进程内跑 ReceivePack，
 * 收包结束会按这个开关（默认 true）触发 auto gc，而 auto gc 默认 {@code gc.autoDetach}
 * 在后台线程里跑、期间持有 {@code gc.log.lock}。测试方法一结束 JUnit 就清理 @TempDir，
 * 与后台 gc 撞上就报「Failed to delete temp directory ... gc.log.lock」（dev-board#500）。
 */
public final class BareHub {

    private BareHub() {}

    /** 建裸仓（初始分支 master，与 JGit 默认一致），返回其 file:// 地址。 */
    public static String init(Path dir) throws Exception {
        try (Git git = Git.init().setBare(true).setDirectory(dir.toFile())
                .setInitialBranch("master").call()) {
            StoredConfig cfg = git.getRepository().getConfig();
            cfg.setBoolean("receive", null, "autogc", false);
            cfg.save();
        }
        return dir.toUri().toString();
    }
}
