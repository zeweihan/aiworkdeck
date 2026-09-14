// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import java.util.List;
import java.util.Locale;

/**
 * 一条版本记录属于哪一类动作（spec 2026-09-14 §2.4 的 {@code type} 字段）。
 *
 * <h3>为什么要有这个类</h3>
 * 时间线上的行现在要认出「初始版本 / 结束工作 / 取回最新稿 / 采纳一稿 / 退回 /
 * 自动存档 / 升级清单」七类，程序员在 IDE 里一眼能看出 merge/revert/initial，
 * 律师这边也得看得出来。判定**只做一处**：前端不猜、`VersionTimeline` 不各写一份，
 * 任何新的展示面都调 {@link #classify}。
 *
 * <h3>为什么是字符串匹配，而不是新加一个尾注</h3>
 * 历史永不重写（地雷 #1）：已经落进版本库的几百笔提交不可能补上新尾注，
 * 而律师最想看清类型的恰恰是既有历史。提交消息是这些提交身上唯一可用的线索。
 *
 * <h3>消息模板来自哪里（改动这些生成点必须同步改这里）</h3>
 * 生成侧都是 {@code LangText.of(zh, en)}——**同一个仓库里可能同时存在中英两种写法**
 * （提交时界面是什么语言就写什么），所以每条都要两个字面量都认：
 * <ul>
 *   <li>{@code ProjectRepoService.init}：「初始版本」/「Initial version」</li>
 *   <li>{@code WorkSessionService.prepareRemoteRepository}：「升级版本记录格式」/
 *       「Upgraded version history format」</li>
 *   <li>{@code WorkSessionService.revertTo}：「退回到早先的版本」/
 *       「Reverted to an earlier version」</li>
 *   <li>{@code WorkSessionService.adoptMessage}：「采纳：」/「Adopt: 」+ 稿名（前缀匹配）</li>
 *   <li>{@code CloudSyncService.cloudMergeTitle}：「取回最新稿」/「Pull Latest」</li>
 * </ul>
 * 这些字面量**没有**被提成公共常量：其中两处落在
 * {@code WorkSessionService}/{@code CloudSyncService} 里、正被另一条线并行改动，
 * 为一个只读的分类器去动那两个类不值当。两边一致由
 * {@code HistoryTypeClassifierContractTest} 钉住——它真的去调生成侧产出消息再来分类，
 * 谁改了文案而没改这里，那条测试当场转红。
 */
public final class HistoryTypeClassifier {

    private HistoryTypeClassifier() {}

    public static final String INITIAL = "initial";
    public static final String SESSION = "session";
    public static final String PULL = "pull";
    public static final String ADOPT = "adopt";
    public static final String REVERT = "revert";
    public static final String AUTO = "auto";
    public static final String UPGRADE = "upgrade";

    /** 见类注释：每条模板的中英两种写法都要认。 */
    private static final List<String> INITIAL_MESSAGES = List.of("初始版本", "Initial version");
    private static final List<String> UPGRADE_MESSAGES =
            List.of("升级版本记录格式", "Upgraded version history format");
    private static final List<String> REVERT_MESSAGES =
            List.of("退回到早先的版本", "Reverted to an earlier version");
    private static final List<String> PULL_MESSAGES = List.of("取回最新稿", "Pull Latest");
    private static final List<String> ADOPT_PREFIXES = List.of("采纳：", "Adopt: ");

    /**
     * @param message 提交标题（{@link VersionEntry#message()}，即提交消息第一行）
     * @param kind    {@code X-AWD-Kind} 尾注（auto / session）
     * @return 七类之一，永不为 null
     *
     * <p>顺序有意义：先按消息认出那五种**具体动作**，再退回 kind。
     * 「初始版本」「退回」这些提交身上的 kind 都是 {@code session}，
     * 先看 kind 就会把它们一律归成「结束工作」，类型标签等于白加。
     */
    public static String classify(String message, String kind) {
        String title = message == null ? "" : message.trim();
        if (matches(title, INITIAL_MESSAGES)) return INITIAL;
        if (matches(title, UPGRADE_MESSAGES)) return UPGRADE;
        if (matches(title, REVERT_MESSAGES)) return REVERT;
        if (matches(title, PULL_MESSAGES)) return PULL;
        if (startsWithAny(title, ADOPT_PREFIXES)) return ADOPT;
        return AUTO.equals(kind) ? AUTO : SESSION;
    }

    private static boolean matches(String title, List<String> candidates) {
        for (String c : candidates) {
            if (c.equalsIgnoreCase(title)) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String title, List<String> prefixes) {
        String lower = title.toLowerCase(Locale.ROOT);
        for (String p : prefixes) {
            if (lower.startsWith(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
