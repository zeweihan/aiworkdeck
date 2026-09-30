// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.QichachaService;
import com.checkba.service.TushareService;
import com.checkba.service.account.AccountException;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.EnterpriseDataTools;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ai.tools.ToolContext;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.legal.PkulawChannel;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolRegistryAccountActionTest {
    private final SseEmitterService sse = mock(SseEmitterService.class);
    private final ToolContext context = new ToolContext(1L, "conv-paid-tools", 7L, null);

    private ToolRegistry registry(List<AgentToolComponent> tools) {
        ToolRegistry registry = new ToolRegistry(tools, new PluginService(), new ClientCapabilityService());
        ReflectionTestUtils.setField(registry, "sseEmitterService", sse);
        registry.init();
        return registry;
    }

    private ToolRegistry paidTools(GatewayException.Kind kind) {
        GatewayException error = new GatewayException(kind, "upstream error");
        PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
        when(gateway.call(anyString(), anyString(), anyMap(), anyInt())).thenThrow(error);
        ExternalProviderResolver resolver = mock(ExternalProviderResolver.class);
        when(resolver.resolve(anyString())).thenReturn(ExternalServiceProvider.PLATFORM);
        WebTools web = new WebTools();
        ReflectionTestUtils.setField(web, "externalProviderResolver", resolver);
        ReflectionTestUtils.setField(web, "platformGatewayClient", gateway);

        PkulawChannel pkulaw = mock(PkulawChannel.class);
        when(pkulaw.callTool(anyString(), anyString(), anyMap())).thenThrow(error);
        LegalTools legal = new LegalTools(null, pkulaw, null);
        QichachaService qichacha = mock(QichachaService.class);
        when(qichacha.queryEciInfoJson(anyString())).thenThrow(error);
        when(qichacha.queryIprJson(anyString(), anyString())).thenThrow(error);
        TushareService tushare = mock(TushareService.class);
        when(tushare.queryJson(anyString(), anyMap(), anyString())).thenThrow(error);
        return registry(List.of(web, legal, new EnterpriseDataTools(qichacha, tushare)));
    }

    @ParameterizedTest
    @EnumSource(value = GatewayException.Kind.class, names = {"NOT_CONNECTED", "UNAUTHORIZED", "NO_CREDITS"})
    void realPaidToolsNotifyUiWithoutTerminatingTheModel(GatewayException.Kind kind) {
        ToolRegistry registry = paidTools(kind);
        for (var request : List.of(
                new String[]{"search_web", "{\"query\":\"合同\"}"},
                new String[]{"law_search", "{\"query\":\"合同\"}"},
                new String[]{"qichacha_query", "{\"companyName\":\"合成公司\"}"},
                new String[]{"qichacha_ipr", "{\"companyName\":\"合成公司\",\"kind\":\"patent\"}"},
                new String[]{"tushare_query", "{\"apiName\":\"stock_basic\"}"})) {
            var result = registry.execute(request[0], request[1], context);
            assertTrue(result.found(), request[0]);
            assertTrue(result.success(), "账户动作不触发模型连续失败纠正: " + result.output());
            assertTrue(result.output().contains("基于已有信息继续"), result.output());
        }
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(sse, times(5)).send(eq(context.conversationId()), eq("account_action_required"), payloads.capture());
        for (Object raw : payloads.getAllValues()) {
            var payload = cn.hutool.json.JSONUtil.parseObj(raw.toString());
            assertEquals(kind.name(), payload.getStr("gatewayKind"));
            assertEquals(kind == GatewayException.Kind.NO_CREDITS ? 1 : 4011, payload.getInt("code"));
        }
        verifyNoMoreInteractions(sse);
    }

    @Test
    void providerOutageDoesNotOpenAccountDialogs() {
        ToolRegistry registry = paidTools(GatewayException.Kind.SERVICE_DISABLED);
        assertTrue(registry.execute("search_web", "{\"query\":\"合同\"}", context).success());
        verifyNoInteractions(sse);
    }

    public static class PaidOperation implements AgentToolComponent {
        @Tool("A paid operation")
        public String paid_operation() {
            throw new AccountException(AccountException.Kind.CONFLICT, "请充值", "no_credits");
        }
    }

    @Test
    void accountExceptionFromPaidToolsPreservesRechargeReason() {
        var result = registry(List.of(new PaidOperation())).execute("paid_operation", "{}", context);
        assertTrue(result.success());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sse).send(eq(context.conversationId()), eq("account_action_required"), payload.capture());
        assertEquals("no_credits", cn.hutool.json.JSONUtil.parseObj(payload.getValue().toString()).getStr("reason"));
    }
}
