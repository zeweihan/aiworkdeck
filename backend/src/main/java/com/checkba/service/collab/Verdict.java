// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/** 资格判定结果。{@code allowed} 为真时 {@code denial} 必为 null。 */
public record Verdict(boolean allowed, Denial denial) {

    public static final Verdict ALLOWED = new Verdict(true, null);

    public static Verdict denied(Denial denial) {
        return new Verdict(false, denial);
    }
}
