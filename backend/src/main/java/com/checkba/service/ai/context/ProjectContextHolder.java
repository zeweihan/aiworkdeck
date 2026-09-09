// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.context;

/**
 * Holds the current Project ID and Conversation ID for the request.
 * Used by AI services to access context without passing parameters.
 */
public class ProjectContextHolder {
    private static final ThreadLocal<String> PROJECT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setProjectId(String projectId) {
        PROJECT_ID.set(projectId);
    }

    public static String getProjectId() {
        return PROJECT_ID.get();
    }

    public static Long getProjectIdAsLong() {
        String id = PROJECT_ID.get();
        if (id == null) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setConversationId(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    public static String getConversationId() {
        return CONVERSATION_ID.get();
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        PROJECT_ID.remove();
        CONVERSATION_ID.remove();
        USER_ID.remove();
    }
}

