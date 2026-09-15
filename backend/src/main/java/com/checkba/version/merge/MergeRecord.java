// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.util.List;

/**
 * 一份文件这一次是怎么合的（spec 2026-09-14 §4.4/§4.6）。
 *
 * <ul>
 *   <li>{@code mode = "auto"}：两边改的地方不重叠，静默合并，不逐处记；
 *       {@code mainCount}/{@code otherCount} 是两侧各合入几处，{@code decisions} 为空。</li>
 *   <li>{@code mode = "manual"}：同一段两边都改了，律师在合并比对稿里逐处裁决；
 *       {@code decisions} 逐处记，两个计数不用（写 0）。</li>
 * </ul>
 *
 * <p>{@code path} 是仓库内相对路径（与 {@code X-AWD-Resolutions} 同一套路径）。
 * 落进提交说明尾注 {@code X-AWD-Merges} 后，读侧解回
 * {@link com.checkba.version.VersionEntry.MergeSummary}——两者字段同构，
 * 分成两个类型只是因为写入侧属于合并包、读出侧属于版本记录的出参。
 */
public record MergeRecord(String path, String mode, List<Decision> decisions,
                          int mainCount, int otherCount) {}
