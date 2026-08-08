package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.ai.subagent.SubAgentProperties;
import com.checkba.service.ai.subagent.SubAgentResult;
import com.checkba.service.ai.subagent.SubAgentService;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 云多租户（strictMultiTenant）下「工具内部的 LLM 调用」必须能取到平台密钥。
 *
 * <p>背景：平台通道身份（{@link PlatformAiUserScope}）在 taskExecutor 线程建立，而工具分发跑在
 * 流式回调线程上、子 Agent 又另起线程——ThreadLocal 都不跟着走。缺身份时
 * {@link PlatformAiChannel} 会抛「本次 AI 调用未携带用户身份」的 AccountException，
 * 而编排器把 AccountException 当成「平台通道不可用」直接终止整轮，用户看到的是一句
 * 与真实原因无关的中文提示。修法是在 {@link ToolRegistry#execute} 与
 * {@link SubAgentService#dispatch} 按 ctx.userId() 重建作用域。
 *
 * <p>这里刻意用**真实**的 PlatformAiChannel（只 mock 它下面的 per-user 密钥库），
 * 因为要守的正是它那条「多租户下缺身份一律拒绝、绝不回落机器级 key」的判定。
 */
@DisplayName("云多租户：工具/子 Agent 线程的平台身份重建")
class PlatformScopeCloudMultiTenantTest {

    private static final Long USER_ID = 7L;
    private static final String PER_USER_KEY = "sk-or-per-user-test";

    private PlatformAiChannel channel;
    private ToolRegistry registry;

    /** 工具内部去取平台密钥（子 Agent、deep_search 的查询扩展在真实链路里就是这样） */
    class PlatformKeyProbeTools implements AgentToolComponent {
        @Tool("Probe the platform channel api key")
        public String platform_key_probe() {
            return "key:" + channel.apiKey();
        }
    }

    @BeforeEach
    void setUp() {
        PlatformAiKeyService perUser = mock(PlatformAiKeyService.class);
        // 本实例上存在桥接绑定 → strictMultiTenant 为真：缺身份不许回落机器级 key
        when(perUser.multiTenant()).thenReturn(true);
        when(perUser.isBound(eq(USER_ID))).thenReturn(true);
        when(perUser.resolve(eq(USER_ID))).thenReturn(
                Optional.of(new PlatformAiKeyService.Resolved(PER_USER_KEY, "fp-test", 5.0)));

        channel = new PlatformAiChannel(mock(AccountService.class), perUser,
                false, System.getProperty("java.io.tmpdir"));
        registry = new ToolRegistry(List.of(new PlatformKeyProbeTools()),
                new PluginService(), new ClientCapabilityService());
        registry.init();
    }

    @Test
    @DisplayName("前提：缺身份时平台通道确实会拒绝（这条红了说明测试本身没意义了）")
    void withoutScopeChannelRefuses() {
        assertNull(PlatformAiUserScope.current());
        AccountException e = assertThrows(AccountException.class, () -> channel.apiKey());
        assertTrue(e.getMessage().contains("未携带用户身份"), "应是缺身份的拒绝：" + e.getMessage());
    }

    @Test
    @DisplayName("工具分发（含 query_memory/deep_search 这类内部会调 LLM 的工具）：不再抛 AccountException")
    void toolDispatchCarriesIdentity() {
        ToolContext ctx = new ToolContext(42L, "conv-main", USER_ID,
                AllowedModels.DEEPSEEK_V4_FLASH.getModelId());

        // 调用线程没有作用域（模拟流式回调线程）
        assertNull(PlatformAiUserScope.current());
        ToolRegistry.ToolResult result = registry.execute("platform_key_probe", "{}", ctx);

        assertTrue(result.success(), "工具不应因缺身份失败：" + result.output());
        assertEquals("key:" + PER_USER_KEY, result.output());
    }

    @Test
    @DisplayName("子 Agent 线程：按 parentCtx.userId 重建作用域，模型调用不再抛 AccountException")
    void subAgentThreadCarriesIdentity() {
        ToolContext parentCtx = new ToolContext(42L, "conv-main", USER_ID,
                AllowedModels.CLAUDE_SONNET_5.getModelId());

        ToolRegistry subRegistry = mock(ToolRegistry.class);
        when(subRegistry.getAllSpecifications(any())).thenReturn(List.of(
                ToolSpecification.builder().name("search_web").description("web search").build()));

        ChatLanguageModel model = mock(ChatLanguageModel.class);
        // 子 Agent 的模型调用在自己的线程池线程上发生：这里去取密钥，缺身份就会抛
        when(model.generate(anyList(), anyList())).thenAnswer(inv ->
                Response.from(AiMessage.from("平台密钥可用：" + channel.apiKey())));

        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.getChatModel(any())).thenReturn(model);

        AuxModelResolver auxModelResolver = mock(AuxModelResolver.class);
        when(auxModelResolver.subAgentModelId(any()))
                .thenReturn(AllowedModels.QWEN_3_7_FLASH.getModelId());

        SubAgentService service = new SubAgentService(subRegistry, factory,
                new XmlToolCallParser(subRegistry), mock(SseEmitterService.class),
                new SubAgentProperties(), auxModelResolver, mock(TokenUsageService.class));

        assertNull(PlatformAiUserScope.current());
        SubAgentResult result = service.dispatch("调研一下", "结论", List.of("search_web"), parentCtx);

        assertTrue(result.success(), "子任务不应因缺身份失败：" + result.error());
        assertTrue(result.result().contains(PER_USER_KEY), "子 Agent 线程应能取到 per-user 平台密钥");
    }
}
