// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import java.time.Instant;
import java.util.List;

/**
 * 一条版本记录。kind 取自提交消息尾注 X-AWD-Kind：
 *   auto    = 工作段内的自动存档
 *   session = 工作段本身（合并节点）
 *
 * milestone 非空即该版本被标记为「重要版本」，取自附注标签
 * refs/tags/awd/milestone/{sha 前 12 位} 的 message（见 ProjectRepoService.tagMilestone）。
 *
 * authorEmail 是提交对象里的作者邮箱原值（不做任何本地化/改写，与 authorName 不同）。
 * 新提交由 {@link VersionAuthorResolver} 合成，是账户级身份的唯一可靠线索；
 * 存量历史里是旧公式，判「是不是我」时由 {@link VersionAuthorResolver#isSelf} 分流。
 *
 * resolutions 非空即这一版是一次**冲突裁决**合并，逐文件记下留了哪一边，
 * 取自提交消息尾注 X-AWD-Resolutions（spec 2026-09-14 §2.2）。
 */
public record VersionEntry(
        String sha,
        String message,
        String authorName,
        String authorEmail,
        Instant when,
        String kind,
        String note,
        List<String> parents,
        String milestone,
        List<Resolution> resolutions
) {
    /**
     * 换一个署名（其余字段原样）。用在**出参侧**把 git 署名翻译成案件库账户的展示名
     * （见 {@link VersionAuthorResolver#preferredAuthorName}）——写入侧一个字都不动，
     * 历史永不重写。
     */
    public VersionEntry withAuthorName(String name) {
        return new VersionEntry(sha, message, name, authorEmail, when, kind, note,
                parents, milestone, resolutions);
    }

    /**
     * 一份文件的裁决结果。{@code kept} 是 MAIN/DRAFT/BOTH 之一（字面量与
     * {@code WorkSessionService.Resolution} 同源），语义按语境翻译成界面词——
     * 三语境的 MAIN/DRAFT 指向哪一侧完全不同，见 version-control.md 的方向表。
     */
    public record Resolution(String path, String kept) {}
}
