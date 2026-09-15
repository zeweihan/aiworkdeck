// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

public class GitAccessDeniedException extends RuntimeException {
    private final int statusCode;
    public GitAccessDeniedException(int statusCode) {
        super("git access denied: " + statusCode);
        this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
