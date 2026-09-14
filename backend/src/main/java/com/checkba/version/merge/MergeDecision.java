// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

/**
 * 这个路径该怎么处理：{@code AUTO} 不打扰律师直接合、{@code MANUAL} 逐处裁决、{@code WHOLE} 退回整份三选一。
 */
public enum MergeDecision {
    AUTO, MANUAL, WHOLE
}
