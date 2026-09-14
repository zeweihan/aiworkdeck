// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.version.merge.Decision;

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
 *
 * mergeContext / merges 来自 spec 2026-09-14（三方合并）§4.6 的两条新尾注：
 * 前者是这一次裁决的语境（adopt / cloud / session-end），**读 resolutions 与 merges
 * 里的 MAIN/DRAFT、M/T 必须带上它**——同一个标签在三语境里指向的物理侧不同；
 * 后者逐文件记下这一次是自动合并的还是逐处裁决的。老提交里两者分别是 null 与空表。
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
        List<Resolution> resolutions,
        String mergeContext,
        List<MergeSummary> merges
) {
    /**
     * 不带合并字段的旧形制（{@code mergeContext = null}、{@code merges} 空表）。
     * 留着是为了不让「多了两个出参字段」去改一堆与合并无关的构造点——
     * 那些地方本来就没有合并信息可填。
     */
    public VersionEntry(String sha, String message, String authorName, String authorEmail,
                        Instant when, String kind, String note, List<String> parents,
                        String milestone, List<Resolution> resolutions) {
        this(sha, message, authorName, authorEmail, when, kind, note, parents,
                milestone, resolutions, null, List.of());
    }

    /**
     * 换一个署名（其余字段原样）。用在**出参侧**把 git 署名翻译成案件库账户的展示名
     * （见 {@link VersionAuthorResolver#preferredAuthorName}）——写入侧一个字都不动，
     * 历史永不重写。
     */
    public VersionEntry withAuthorName(String name) {
        return new VersionEntry(sha, message, name, authorEmail, when, kind, note,
                parents, milestone, resolutions, mergeContext, merges);
    }

    /**
     * 一份文件的裁决结果。{@code kept} 是 MAIN/DRAFT/BOTH/MERGED 之一（前三个字面量与
     * {@code WorkSessionService.Resolution} 同源，MERGED 是三方合并那一档：这份文件
     * 已经逐处合好写回工作区了，见 spec 2026-09-14 §4.4），语义按语境翻译成界面词——
     * 三语境的 MAIN/DRAFT 指向哪一侧完全不同，见 version-control.md 的方向表。
     */
    public record Resolution(String path, String kept) {}

    /**
     * 一份文件这一次是怎么合的，取自尾注 X-AWD-Merges（spec 2026-09-14 §4.6）。
     * 与写入侧的 {@link com.checkba.version.merge.MergeRecord} 字段同构：
     * {@code mode = "auto"} 时只有两个计数（两边各合入几处），
     * {@code mode = "manual"} 时只有 {@code decisions}（逐处裁决）。
     * 尾注里逐处清单超过 500 条会被截断，读回来就只有前 500 条——
     * 它是给律师看的说明，不是可回放的操作日志。
     */
    public record MergeSummary(String path, String mode, List<Decision> decisions,
                               int mainCount, int otherCount) {}
}
