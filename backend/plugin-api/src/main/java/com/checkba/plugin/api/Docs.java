// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 编辑器桥：仅在有 conversationId 的工具调用期可用；无会话抛 IllegalStateException("no active conversation")。 */
public interface Docs {
    /** action 必须在宿主 EDITOR_ACTIONS 白名单内；返回编辑器回传的 JSON。 */
    String exec(String action, java.util.Map<String, Object> params);
    void refreshFiles();
    void openFile(long fileId, java.util.Map<String, Object> locator);
}
