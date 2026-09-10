// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

public record MemorySpaceView(String id, String scope, String label, boolean readable,
                              boolean writable, boolean available, String reason) {
    public static MemorySpaceView unavailable(String scope, String label, String reason) {
        return new MemorySpaceView(null, scope, label, false, false, false, reason);
    }
}
