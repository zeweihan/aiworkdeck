// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

/**
 * 一次参考来源调用的上下文。userId / projectId / conversationId 一律取自服务端上下文
 * （ToolRegistry 的 SERVER_CONTEXT_PARAMS 强制注入），模型传的同名值不可信、已被覆盖。
 *
 * @param query 仅 list 使用：文件名关键字，可为 null（不过滤）
 */
public record RefQuery(Long userId, Long projectId, String conversationId, String query) {
}
