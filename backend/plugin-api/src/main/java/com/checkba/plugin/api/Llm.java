// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** LLM 补全：走平台通道、扣用户 Credits、记 pluginId。 */
public interface Llm {
    String complete(String systemPrompt, String userPrompt, LlmOptions o);
}
