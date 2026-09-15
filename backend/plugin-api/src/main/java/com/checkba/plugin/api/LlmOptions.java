// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** LLM 调用选项；modelId 为 null 时宿主用辅助模型（便宜档）。 */
public record LlmOptions(String modelId, double temperature, int maxTokens) {
    public static LlmOptions cheap() { return new LlmOptions(null, 0.0, 2048); }
}
