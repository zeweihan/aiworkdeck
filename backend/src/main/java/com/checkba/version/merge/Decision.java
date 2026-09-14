// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * 合并比对稿里对**一处**改动的处置（spec 2026-09-14 §4.4）。
 *
 * <ul>
 *   <li>{@code key}：改动所在的单元，与 {@code Unit.key()} 同一套写法——
 *       docx 正文段落 {@code p12}、docx 表格单元 {@code t1.2.3}、xlsx 单元格
 *       {@code Sheet1!B7}、pptx 页 {@code s3}。docx 的段落序取**合并结果里**的序，
 *       不是基线序：律师事后点开这一版看到的就是合并后的文档。</li>
 *   <li>{@code side}：{@code M} = 合并时的主线那一侧，{@code T} = 另一边；
 *       律师自己改的（{@code X}）与只是记录一笔的格式改动（{@code F}）没有侧别，写空串。
 *       <b>M/T 是物理侧，不是「我 / 同事」</b>——三语境里指向的人不同，翻译时必须带上
 *       {@code mergeContext}，方向表见 {@code .claude/agents/version-control.md}。</li>
 *   <li>{@code action}：{@code A} 接受该侧、{@code R} 拒绝该侧、{@code X} 律师自己改了这一处、
 *       {@code F} 另一侧只改了格式、没自动合过来（仅记录）。</li>
 * </ul>
 *
 * <p>这三个字段会逐字写进用户产物——项目 Git 仓库的提交说明尾注 {@code X-AWD-Merges}
 * （见 {@code ProjectRepoService.mergesTrailerValue}），律师把仓库 clone 出去用
 * {@code git log} 仍然读得到，因此字面量不是内部约定，改了就是破坏兼容。
 */
public record Decision(String key, String side, String action) {}
