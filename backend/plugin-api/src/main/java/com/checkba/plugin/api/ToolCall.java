// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 一次工具调用的上下文快照：项目、会话、用户、模型。 */
public record ToolCall(Long projectId, String conversationId, Long userId, String modelId) {}
