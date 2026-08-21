package com.checkba.service.ai.subagent;

import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.XmlToolCallParser;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubAgentService 单测：循环上限 / token 预算 / 超时 / 防递归 / 工具域 / 身份继承（不变式 3）
 * / 模型解析链与白名单护栏 / 每轮记账。
 * LLM 用 Mockito 打桩，ToolRegistry 用 mock 记录分发（不真执行工具）。
 */
@DisplayName("子 Agent 服务（Phase 3C）")
class SubAgentServiceTest {

    /** 主会话选的是贵模型：子 Agent 默认不再继承它（省钱语义） */
    private static final ToolContext PARENT_CTX =
            new ToolContext(42L, "conv-main", 7L, AllowedModels.CLAUDE_SONNET_5.getModelId());

    /** 默认解析到的子 Agent 模型 = 辅助模型 */
    private static final String AUX_MODEL = AllowedModels.QWEN_3_7_FLASH.getModelId();

    private ToolRegistry registry;
    private ChatModelFactory modelFactory;
    private ChatLanguageModel model;
    private SseEmitterService sse;
    private SubAgentProperties props;
    private AuxModelResolver auxModelResolver;
    private TokenUsageService tokenUsageService;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        // 子 Agent 走会话能力感知的 getAllSpecifications(conversationId)（Phase C）
        when(registry.getAllSpecifications(any())).thenReturn(List.of(
                ToolSpecification.builder().name("search_web").description("web search").build(),
                ToolSpecification.builder().name("read_document").description("read file").build(),
                ToolSpecification.builder().name("dispatch_subtask").description("delegate").build()));
        when(registry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("tool output (test stub)", null, true));

        model = mock(ChatLanguageModel.class);
        modelFactory = mock(ChatModelFactory.class);
        when(modelFactory.getChatModel(any())).thenReturn(model);

        sse = mock(SseEmitterService.class);
        props = new SubAgentProperties();

        // 默认：ai.subagentModel / yml 都没配 → 回退辅助模型
        auxModelResolver = mock(AuxModelResolver.class);
        when(auxModelResolver.subAgentModelId(any())).thenReturn(AUX_MODEL);
        when(auxModelResolver.auxModelId()).thenReturn(AUX_MODEL);
        tokenUsageService = mock(TokenUsageService.class);
    }

    private SubAgentService newService() {
        return new SubAgentService(registry, modelFactory, new XmlToolCallParser(registry), sse, props,
                auxModelResolver, tokenUsageService);
    }

    private static Response<AiMessage> toolCallTurn(String toolName, String argsJson) {
        return Response.from(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-" + toolName).name(toolName).arguments(argsJson).build()));
    }

    private static Response<AiMessage> textTurn(String text) {
        return Response.from(AiMessage.from(text));
    }

    @Test
    @DisplayName("身份继承（不变式 3）：子 Agent 工具调用的 ToolContext 与主会话一致 + 进度事件成对发送")
    void identityInheritedFromParentContext() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("search_web", "{\"query\":\"对赌协议\"}"),
                textTurn("要点：1……"));

        SubAgentResult result = newService().dispatch("调研对赌协议裁判观点", "要点列表",
                List.of("search_web"), PARENT_CTX);

        assertTrue(result.success(), "应成功: " + result.toJson());
        assertEquals(List.of("search_web"), result.toolsUsed());
        assertEquals(2, result.rounds());
        assertTrue(result.result().contains("要点"));

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(registry).execute(eq("search_web"), any(), ctxCaptor.capture());
        ToolContext used = ctxCaptor.getValue();
        assertNotNull(used);
        assertEquals(PARENT_CTX.projectId(), used.projectId(), "projectId 必须继承主会话");
        assertEquals(PARENT_CTX.conversationId(), used.conversationId(), "conversationId 必须继承主会话");
        assertEquals(PARENT_CTX.userId(), used.userId(), "userId 必须继承主会话");
        // 模型不再继承主会话：未配置时落到便宜的辅助模型（省钱语义，见 SubAgentProperties.model）
        assertEquals(AUX_MODEL, used.modelId());

        // 子任务开始/结束各发一次 subtask_progress（发到主会话 conversationId）
        verify(sse, times(2)).send(eq("conv-main"), eq("subtask_progress"), any());
    }

    @Test
    @DisplayName("防递归：子 Agent 内调用 dispatch_subtask 被拒绝，不经 ToolRegistry 分发")
    void recursionRefused() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("dispatch_subtask", "{\"task_description\":\"再拆一层\"}"),
                textTurn("最终结果"));

        SubAgentResult result = newService().dispatch("任务", "结果", List.of(), PARENT_CTX);

        assertTrue(result.success());
        // dispatch_subtask 从未真正分发
        verify(registry, never()).execute(eq("dispatch_subtask"), any(), any());
        assertTrue(result.toolsUsed().isEmpty(), "拒绝的调用不计入 toolsUsed");

        // 拒绝错误作为工具结果回喂给了子模型
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(model, atLeastOnce()).generate(messagesCaptor.capture(), anyList());
        boolean refusalFedBack = messagesCaptor.getValue().stream()
                .anyMatch(m -> {
                    try {
                        return m.text() != null && m.text().contains("nested delegation refused");
                    } catch (Exception e) {
                        return false;
                    }
                });
        assertTrue(refusalFedBack, "子模型应收到明确的递归拒绝错误");
    }

    @Test
    @DisplayName("轮数上限：耗尽后返回明确失败结果")
    void maxRoundsExceeded() {
        props.setMaxRounds(2);
        when(model.generate(anyList(), anyList()))
                .thenReturn(toolCallTurn("search_web", "{\"query\":\"a\"}"))
                .thenReturn(toolCallTurn("search_web", "{\"query\":\"b\"}"));

        SubAgentResult result = newService().dispatch("查不完的任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("max rounds"), "错误信息应说明轮数耗尽: " + result.error());
        assertEquals(2, result.rounds());
        assertEquals(List.of("search_web", "search_web"), result.toolsUsed());
    }

    @Test
    @DisplayName("token 预算：超出后立即中止，不再调用 LLM")
    void tokenBudgetExceeded() {
        props.setTokenBudget(1);
        props.setCharsPerToken(1.0);

        SubAgentResult result = newService().dispatch("这条任务描述本身就超过一个 token 的预算了",
                null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("token budget"), "错误信息应说明预算超限: " + result.error());
        verify(model, never()).generate(anyList(), anyList());
    }

    @Test
    @DisplayName("超时：返回明确错误结果，不挂死调用方")
    void timeoutReturnsExplicitError() {
        props.setTimeoutSeconds(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return textTurn("太迟了");
        });

        SubAgentResult result = newService().dispatch("慢任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("timed out"), "错误信息应说明超时: " + result.error());
    }

    @Test
    @DisplayName("修复：超时前已经真实跑完的轮次与工具调用，不能被超时结果抹成空列表/0")
    void timeoutPreservesRealPartialProgress() {
        props.setTimeoutSeconds(1);
        // 第一轮：正常调用一次工具（真实执行，有副作用）；第二轮：模型调用直接卡住触发超时
        when(model.generate(anyList(), anyList()))
                .thenReturn(toolCallTurn("search_web", "{\"query\":\"x\"}"))
                .thenAnswer(inv -> {
                    Thread.sleep(10_000);
                    return textTurn("太迟了");
                });

        SubAgentResult result = newService().dispatch("慢任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("timed out"), "错误信息应说明超时: " + result.error());
        assertEquals(List.of("search_web"), result.toolsUsed(),
                "第一轮已经真实调用过 search_web，超时结果不该把它抹成空列表");
        assertEquals(1, result.rounds(), "第一轮已完整跑完，rounds 不该恒为 0");
    }

    @Test
    @DisplayName("工具域：scope 外的工具被拒绝，不经 ToolRegistry 分发")
    void toolScopeEnforced() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("read_document", "{\"fileId\":\"1\"}"),
                textTurn("好的，我不读文件了，直接给结论"));

        SubAgentResult result = newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        assertTrue(result.success());
        verify(registry, never()).execute(eq("read_document"), any(), any());
        assertTrue(result.toolsUsed().isEmpty());
    }

    @Test
    @DisplayName("省钱语义：未配置子 Agent 模型时用辅助模型建模，而不是主会话的贵模型")
    void defaultsToAuxModelInsteadOfParentModel() {
        when(model.generate(anyList(), anyList())).thenReturn(textTurn("结论"));

        newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        verify(modelFactory).getChatModel(AUX_MODEL);
        verify(modelFactory, never()).getChatModel(PARENT_CTX.modelId());
    }

    @Test
    @DisplayName("护栏：子 Agent 模型不在白名单时拒绝派发（不静默回落成主模型），提示可读")
    void rejectsModelOutsideAllowList() {
        when(auxModelResolver.subAgentModelId(any())).thenReturn("vendor/not-in-allowlist");

        SubAgentResult result = newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("vendor/not-in-allowlist"), "提示要点名是哪个模型：" + result.error());
        assertTrue(result.error().contains("设置页"), "提示要能行动：" + result.error());
        // 账户类文案禁用子串（api.js 用它们判掉线会清会话）
        assertFalse(result.error().contains("登录"));
        assertFalse(result.error().contains("未授权"));
        assertFalse(result.error().contains("请先"));
        // 一次模型都没建、一次 LLM 都没调
        verify(modelFactory, never()).getChatModel(any());
        verify(model, never()).generate(anyList(), anyList());
        // 进度事件仍成对（前端子任务卡按 taskId 配对渲染，只发 failed 会渲染不出来）
        verify(sse, times(2)).send(eq("conv-main"), eq("subtask_progress"), any());
    }

    @Test
    @DisplayName("记账：每一轮的 tokenUsage 都落 token_usage，归属主会话的 project/conversation/user")
    void recordsTokenUsagePerRound() {
        when(model.generate(anyList(), anyList())).thenReturn(
                Response.from(AiMessage.from(ToolExecutionRequest.builder()
                                .id("r1").name("search_web").arguments("{\"query\":\"a\"}").build()),
                        new TokenUsage(100, 20)),
                Response.from(AiMessage.from("最终结论"), new TokenUsage(300, 40)));

        SubAgentResult result = newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        assertTrue(result.success());
        verify(tokenUsageService).recordUsage(eq(42L), eq(7L), eq(AUX_MODEL),
                eq(new TokenUsage(100, 20)), eq("conv-main"));
        verify(tokenUsageService).recordUsage(eq(42L), eq(7L), eq(AUX_MODEL),
                eq(new TokenUsage(300, 40)), eq("conv-main"));
    }

    @Test
    @DisplayName("平台身份：子 Agent 线程按 parentCtx.userId 重建作用域（提交线程没有作用域也成立）")
    void rebuildsPlatformScopeOnSubAgentThread() {
        AtomicReference<Long> seenInSubAgentThread = new AtomicReference<>();
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            seenInSubAgentThread.set(PlatformAiUserScope.current());
            return textTurn("结论");
        });

        // 提交线程刻意不建立作用域：真实链路里工具分发跑在流式回调线程上，
        // 请求线程的 ThreadLocal 不跟着走——缺身份时云多租户会抛「未携带用户身份」
        assertNull(PlatformAiUserScope.current());
        SubAgentResult result = newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        assertTrue(result.success());
        assertEquals(PARENT_CTX.userId(), seenInSubAgentThread.get(),
                "子 Agent 线程里必须能取到主会话身份，否则平台通道拿不到 per-user 密钥");
    }

    // ===== 任务级取消（长任务可控） =====

    /**
     * 停一个正在跑的子任务：结果必须明说「是用户停的、不要自动重派」——
     * 只回一句 failed 的话，主循环下一轮会立刻再 dispatch 一次，
     * 用户看到的是「点了停止反而又跑起来了」。
     * 顺带守住鉴权：换一个 conversationId 停不掉别人会话里的子任务。
     */
    @Test
    @DisplayName("取消：停掉在跑的子任务，结果明说是用户停的；跨会话停不动")
    void cancelStopsRunningSubtask() throws Exception {
        java.util.concurrent.CountDownLatch llmEntered = new java.util.concurrent.CountDownLatch(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            llmEntered.countDown();
            Thread.sleep(10_000); // 模拟在途 LLM 调用（cancel(true) 打不断它）
            return textTurn("太迟了");
        });
        // 用 doAnswer 收事件而不是事后 verify：dispatch 跑在另一个线程上，
        // 并发 invoke + verify 会让断言时序敏感
        java.util.concurrent.BlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
        org.mockito.Mockito.doAnswer(inv -> {
            // 必须显式给出类型（getArgument(2, String.class)）。写成
            // String.valueOf(inv.getArgument(2)) 会踩 Java 重载决议：getArgument 是泛型
            // <T> T，编译期挑最具体的 String.valueOf(char[]) 并把 T 推成 char[]，
            // 运行时传进来的是 String 就抛 ClassCastException——而异常被 sendProgress
            // 的 catch 吞掉只 log.warn，于是队列永远空、断言以「没收到 started 事件」失败，
            // 看起来像生产代码不发事件，其实是测试自己炸了。
            events.add(inv.getArgument(2, String.class));
            return null;
        }).when(sse).send(eq("conv-main"), eq("subtask_progress"), any());

        SubAgentService service = newService();
        AtomicReference<SubAgentResult> out = new AtomicReference<>();
        Thread caller = new Thread(() ->
                out.set(service.dispatch("慢任务", null, List.of("search_web"), PARENT_CTX)));
        caller.setDaemon(true);
        caller.start();

        assertTrue(llmEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "子 Agent 应已进入 LLM 调用");
        String started = events.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(started, "应先收到 started 进度事件");
        String subtaskId = cn.hutool.json.JSONUtil.parseObj(started).getStr("taskId");
        assertNotNull(subtaskId);

        assertFalse(service.cancel(subtaskId, "conv-other"), "别的会话不得停掉本会话的子任务");
        assertTrue(service.cancel(subtaskId, "conv-main"), "本会话应能停掉自己的子任务");

        caller.join(10_000);
        SubAgentResult result = out.get();
        assertNotNull(result, "dispatch 必须返回，不能挂死调用方");
        assertFalse(result.success());
        assertTrue(result.error().contains("stopped by the user"), "要点明是用户停的：" + result.error());
        assertTrue(result.error().contains("Do NOT"), "要禁止自动重派：" + result.error());
        // 结束事件仍与 started 成对（前端按 taskId 配对渲染）
        String finished = events.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(finished, "停止后仍要发一条结束事件");
        assertTrue(cn.hutool.json.JSONUtil.parseObj(finished).getStr("message").contains("已停止"),
                "给用户看的文案不该是模型看的那段英文：" + finished);
    }

    @Test
    @DisplayName("取消：不存在或已结束的子任务返回 false（登记簿在 dispatch 返回前已移除）")
    void cancelUnknownOrFinishedSubtask() {
        when(model.generate(anyList(), anyList())).thenReturn(textTurn("结论"));
        SubAgentService service = newService();

        assertFalse(service.cancel("subtask-nope", "conv-main"));
        assertFalse(service.cancel(null, "conv-main"));

        SubAgentResult done = service.dispatch("任务", null, List.of("search_web"), PARENT_CTX);
        assertTrue(done.success());
        assertFalse(service.cancel(done.subtaskId(), "conv-main"), "跑完的子任务不再可停");
    }

    @Test
    @DisplayName("tool_scope 容错解析：JSON 数组与逗号/顿号分隔均可")
    void parseToolScopeTolerant() {
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("[\"search_web\", \"browse_url\"]"));
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("search_web, browse_url"));
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("search_web、browse_url"));
        assertTrue(com.checkba.service.ai.tools.SubAgentTools.parseToolScope("  ").isEmpty());
        assertTrue(com.checkba.service.ai.tools.SubAgentTools.parseToolScope(null).isEmpty());
    }
}
