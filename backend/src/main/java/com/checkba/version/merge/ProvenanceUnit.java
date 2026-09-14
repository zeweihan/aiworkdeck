// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import java.time.Instant;

/**
 * 一个单元（docx 一段 / docx 表格一格 / xlsx 一格 / pptx 一页）的出处：这一处文字
 * 最后是哪一版改的、谁改的、什么时候、那一版叫什么。
 *
 * <p>{@code key} 与 {@link Unit#key()} 同一套写法（{@code p12} / {@code t1.2.3} /
 * {@code Sheet1!B7} / {@code s3}）。
 *
 * <p>{@code textHash} 是这一处归一后文字的 SHA-256（十六进制小写）。前端不按 {@code key}
 * 硬对——后端的段序是**落版那一刻**的，律师手上还没保存的插入/删除会把它推着漂——
 * 而是拿这个哈希与画布上的段落做一次 LCS 对齐（{@code utils/provenanceAlign.js}）。
 * 所以归一口径（NFC + 空白折一 + trim）与编码（UTF-8）是**跨端契约**，两边都改才算改。
 *
 * <p>{@code sha} 为 null 表示回溯到了窗口边界（见
 * {@link ProvenanceService#MAX_HISTORY}），这一处只说得出「更早的版本」。
 * 界面上永远不显示 username，作者名走案件库展示名映射。
 */
public record ProvenanceUnit(String key, String textHash, String sha, String shortId,
                             String authorName, boolean self, Instant when,
                             String title, String type) {
}
