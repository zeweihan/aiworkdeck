// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

public record FileChange(String path, Type type) {
    public enum Type { ADD, MODIFY, DELETE, RENAME }
}
