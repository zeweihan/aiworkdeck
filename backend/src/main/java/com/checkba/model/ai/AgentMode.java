// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.ai;

/**
 * Agent 运行模式枚举。
 * 参考 Cursor 的三种模式设计。
 */
public enum AgentMode {
    /**
     * Ask 模式：纯对话模式。
     * - 仅回答问题，不调用任何工具
     * - 可以有上下文（文件、历史记录）
     * - 适合咨询、问答场景
     */
    ASK,

    /**
     * Plan 模式：规划模式。
     * - 复杂任务先生成实施计划
     * - 必须等待用户确认后才能执行
     * - 适合需要审批的复杂任务
     */
    PLAN,

    /**
     * Agent 模式：自动执行模式（默认）。
     * - 可以生成计划，但无需用户确认
     * - 自动调用工具执行任务
     * - 适合简单到中等复杂度任务
     */
    AGENT;

    /**
     * 从字符串解析模式，不区分大小写。
     * 默认返回 AGENT 模式。
     */
    public static AgentMode fromString(String mode) {
        if (mode == null || mode.isEmpty()) {
            return AGENT;
        }
        try {
            return AgentMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}

