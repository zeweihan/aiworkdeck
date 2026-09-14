// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/** 冲突文件的比对粒度。{@code WHOLE} = 没有可比对的单元，只能整份选择。 */
public enum MergeKind {
    DOCX, XLSX, PPTX, WHOLE
}
