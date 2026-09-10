// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import java.util.List;

public interface MemoryOrganizationGateway {
    List<MemorySpaceView> listSpaces(Long userId);
    List<MemoryFileView> listFiles(Long userId, String spaceId);
    List<MemoryFileView> search(Long userId, String spaceId, String query, int limit);
    MemoryFileView read(Long userId, String spaceId, String path);
    MemoryFileView write(Long userId, String spaceId, String path, String content, long expectedRevision);
    void delete(Long userId, String spaceId, String path, long expectedRevision);
    String download(Long userId, String spaceId, String path);

    default boolean handles(String spaceId) {
        return spaceId != null && (spaceId.startsWith("team:") || spaceId.startsWith("firm:"));
    }
}
