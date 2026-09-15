// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.ConversationFileChange;
import com.checkba.repository.ConversationFileChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for managing file changes within AI conversations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationFileChangeService {

    private final ConversationFileChangeRepository repository;

    /**
     * Save a file change record for a conversation.
     */
    public void saveFileChange(String conversationId, String fileName, String changeType) {
        // Avoid duplicates within same conversation
        List<ConversationFileChange> existing = repository.findByConversationId(conversationId);
        boolean duplicate = existing.stream()
                .anyMatch(c -> c.getFileName().equals(fileName) && c.getChangeType().equals(changeType));
        
        if (!duplicate) {
            ConversationFileChange change = new ConversationFileChange(conversationId, fileName, changeType);
            repository.save(change);
            log.debug("Saved file change: conv={}, file={}, type={}", conversationId, fileName, changeType);
        }
    }

    /**
     * Get all file changes for a conversation.
     */
    public List<ConversationFileChange> findByConversationId(String conversationId) {
        return repository.findByConversationId(conversationId);
    }
}
