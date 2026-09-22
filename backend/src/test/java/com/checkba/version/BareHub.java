// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import java.nio.file.Path;

/**
 * 测试用 JGit 仓库的隐式自动 gc 一律要关掉,{@link #init} 建裸仓与 {@link #quiesceAutoGc}
 * 镇住任意已打开仓库是这件事的唯一办法。
 *
 * <p>两条独立的路都会在仓库的 gitDir 下摸一次 {@code gc.log}/{@code gc.log.lock},
 * 背后跑的是 JGit 内部共享的 {@code WorkQueue} 后台线程——测试方法一结束 JUnit
 * 就清理 {@code @TempDir},跟这个后台线程撞上就报
 * 「Failed to delete temp directory ... gc.log.lock」(dev-board#500,dev-board#820):
 * <ul>
 *   <li><b>接收 push 的那一侧</b>——往 file:// 远端 push 时 JGit 在本进程内跑
 *   ReceivePack,收包结束按 {@code receive.autogc}(默认 true)触发 auto gc。这条只挡得住
 *   "接收 push 的仓库自己"，管不到下面那条。</li>
 *   <li><b>发起 fetch 的那一侧</b>——{@code Transport.fetch()} 收尾时不看
 *   {@code receive.autogc},无条件对"拉取方"自己的仓库调一次
 *   {@code Repository.autoGC()}(clone 内部也是一次 fetch,同样会摸)。
 *   这条要靠 {@code gc.auto=0} 关掉(等价于原生 git 里 {@code gc.auto=0} 禁用自动 gc)、
 *   {@code gc.autoDetach=false} 兜底(万一还是跑了,也在调用线程里同步跑完,不留后台线程)。</li>
 * </ul>
 * 三个开关一起关,不管这个仓库在某次操作里扮演的是"接收方"还是"发起方"都盖得住。
 * 任何测试只要会让某个 @TempDir 里的仓库做 fetch/clone/收 push,建完后都要调一次。
 */
public final class BareHub {

    private BareHub() {}

    /** 建裸仓（初始分支 master，与 JGit 默认一致），返回其 file:// 地址。 */
    public static String init(Path dir) throws Exception {
        try (Git git = Git.init().setBare(true).setDirectory(dir.toFile())
                .setInitialBranch("master").call()) {
            quiesceAutoGc(git.getRepository());
        }
        return dir.toUri().toString();
    }

    /** 关掉一个已打开仓库自己的隐式自动 gc（不管它接下来是当接收方还是发起方）。 */
    public static void quiesceAutoGc(Repository repo) throws Exception {
        StoredConfig cfg = repo.getConfig();
        cfg.setBoolean("receive", null, "autogc", false);
        cfg.setInt("gc", null, "auto", 0);
        cfg.setBoolean("gc", null, "autoDetach", false);
        cfg.save();
    }
}
