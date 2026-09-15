// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import java.time.LocalDateTime;

public record MemoryFileView(String path, String title, String content, long revision,
                             LocalDateTime updatedAt, boolean writable) {}
