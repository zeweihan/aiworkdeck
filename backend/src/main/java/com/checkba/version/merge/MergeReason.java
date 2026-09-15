// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * 判定的理由。界面要把它翻成人话（「PDF 与图片没有可比对的段落，只能整份选择」之类），
 * 所以每加一个值都要同步加文案。
 */
public enum MergeReason {
    /** 算不出共同的上一版。 */
    NO_BASE,
    /** 文件类型本身没有可比对的单元（pdf、图片等）。 */
    BINARY,
    /** 类型支持但这一次没法结构化处理（上层服务用：超时等）。 */
    UNSUPPORTED,
    /** 同一处两边都改了（含相邻）。 */
    OVERLAP,
    /** 两边的改动互不相邻，可以自动合。 */
    CLEAN,
    /** 文件太大，不做结构化比对。 */
    TOO_LARGE,
    /** 任一侧读不开。 */
    PARSE_FAILED
}
