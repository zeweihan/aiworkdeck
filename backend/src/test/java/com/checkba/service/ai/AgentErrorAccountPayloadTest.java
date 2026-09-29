// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountRequired;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE {@code error} 事件在账户类失败时带 code / kind（登录后置 4011 契约，dev-board#1046）。
 *
 * <p>只加不改：{@code message} 仍是原来那句文案，插件侧 chatSession.js 本来就读
 * {@code JSON.parse(data).message}；桌面 useAgentStream 见到 {@code code:4011} 就地弹登录层。
 */
class AgentErrorAccountPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("未连接账户：code=4011 + kind=NOT_CONNECTED + reason=platform_ai，message 原样")
    void notConnectedCarries4011() throws Exception {
        JsonNode n = MAPPER.readTree(AgentOrchestrator.accountErrorPayload(
                AccountRequired.exception(AccountRequired.REASON_PLATFORM_AI, "「AI WorkDeck 云端」需要连接账户")));
        assertEquals(4011, n.get("code").asInt());
        assertEquals("NOT_CONNECTED", n.get("kind").asText());
        assertEquals("platform_ai", n.get("reason").asText());
        assertEquals("「AI WorkDeck 云端」需要连接账户", n.get("message").asText());
    }

    @Test
    @DisplayName("NOT_CONNECTED 没带 reason（老抛出点）：AI 通道上默认 platform_ai")
    void notConnectedDefaultsReason() throws Exception {
        JsonNode n = MAPPER.readTree(AgentOrchestrator.accountErrorPayload(
                new AccountException(AccountException.Kind.NOT_CONNECTED, "x")));
        assertEquals(4011, n.get("code").asInt());
        assertEquals("platform_ai", n.get("reason").asText());
    }

    @Test
    @DisplayName("其余账户失败（如未分配额度）：code=1 + kind，不弹登录层")
    void otherKindsAreCodeOne() throws Exception {
        JsonNode n = MAPPER.readTree(AgentOrchestrator.accountErrorPayload(
                new AccountException(AccountException.Kind.CONFLICT, "请先在官网账户页分配 AI 额度")));
        assertEquals(1, n.get("code").asInt());
        assertEquals("CONFLICT", n.get("kind").asText());
        assertFalse(n.has("reason"));
        assertEquals("请先在官网账户页分配 AI 额度", n.get("message").asText());
    }

    @Test
    @DisplayName("文案里的引号与换行被正确转义（载荷必须是合法 JSON）")
    void escapesMessage() throws Exception {
        JsonNode n = MAPPER.readTree(AgentOrchestrator.accountErrorPayload(
                new AccountException(AccountException.Kind.NETWORK, "a\"b\nc")));
        assertEquals("a\"b\nc", n.get("message").asText());
    }
}
