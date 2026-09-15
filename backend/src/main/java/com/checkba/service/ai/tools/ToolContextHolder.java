// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

/**
 * 工具执行期间的 ThreadLocal 上下文。
 * 由 ToolRegistry 在调用工具方法前设置、调用结束后清理，
 * 供工具实现读取无法（或不应）出现在 @Tool 签名里的上下文信息，
 * 例如用户当前选择的模型 ID。
 */
public class ToolContextHolder {

    private static final ThreadLocal<ToolContext> CONTEXT = new ThreadLocal<>();

    public static void set(ToolContext context) {
        CONTEXT.set(context);
    }

    public static ToolContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 当前调用的模型 ID（无上下文时返回 null）。
     */
    public static String currentModelId() {
        ToolContext ctx = CONTEXT.get();
        return ctx != null ? ctx.modelId() : null;
    }
}
