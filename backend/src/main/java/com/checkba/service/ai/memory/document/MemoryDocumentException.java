// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

public class MemoryDocumentException extends RuntimeException {
    private final int status;

    public MemoryDocumentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
