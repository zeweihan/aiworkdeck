package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * runLoop 自动 compaction。
 *
 * <p>两条硬约束：短会话（回放评测用例）绝不能被误触发；折叠后工具调用与工具结果必须仍然配对，
 * 否则 OpenAI 兼容通道会以「tool message 没有对应 tool_calls」直接 400。
 */
class RunLoopCompactorTest {

    private AiContextProperties properties;
    private ContextCompressor compressor;
    private RunLoopCompactor compactor;

    @BeforeEach
    void setUp() {
        properties = new AiContextProperties();
        compressor = mock(ContextCompressor.class);
        // 历史可用预算 1000 token，触发比例 0.8 → 阈值 800 token（chars-per-token=2 → 1600 字符）
        when(compressor.getAvailableTokensForHistory(any())).thenReturn(1000);
        compactor = new RunLoopCompactor(properties, compressor);
    }

    private static ChatMessage toolCall(String id, String name, String args) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(args).build());
    }

    private static ChatMessage toolResult(String id, String name, String text) {
        return ToolExecutionResultMessage.from(id, name, text);
    }

    private static String filler(int chars) {
        return "内容".repeat(chars / 2);
    }

    /** 一段长到必然超阈值的 runLoop 消息栈：system + 用户目标 + N 组「调用 / 结果」 */
    private static List<ChatMessage> longRun(int rounds, int charsPerResult) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("system prompt"));
        messages.add(UserMessage.from("把这份合同的违约金条款改成 30%"));
        for (int i = 0; i < rounds; i++) {
            messages.add(toolCall("c" + i, "doc_read", "{\"page\":" + i + "}"));
            messages.add(toolResult("c" + i, "doc_read", "第 " + i + " 段：" + filler(charsPerResult)));
        }
        return messages;
    }

    @Test
    @DisplayName("短会话不触发：回放评测用例只有几百字符，压缩会平白丢上下文")
    void shortConversationIsUntouched() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("你好"),
                AiMessage.from("你好，有什么可以帮你的？")));

        assertSame(messages, compactor.compact(messages, "anthropic/claude-3.5-sonnet"),
                "未超阈值必须原样返回同一个列表实例");
    }

    @Test
    @DisplayName("消息很长但中段不够条数：宁可不压，压了收益也抵不上丢的上下文")
    void longButShallowStackIsUntouched() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from(filler(4000)),
                AiMessage.from(filler(4000))));

        assertSame(messages, compactor.compact(messages, null));
    }

    @Test
    @DisplayName("超阈值：折叠中段，system prompt、首条用户消息与最近若干轮全部保留")
    void compactsMiddleKeepingHeadAndTail() {
        List<ChatMessage> messages = longRun(12, 400);
        int before = compactor.estimateTokens(messages);
        assertTrue(before > compactor.triggerThreshold(null), "用例前提：必须超阈值");

        List<ChatMessage> result = compactor.compact(messages, null);

        assertTrue(result.size() < messages.size(), "应发生折叠");
        assertEquals(SystemMessage.class, result.get(0).getClass(), "system prompt 必须留在最前");
        assertTrue(((UserMessage) result.get(1)).singleText().contains("违约金"),
                "首条用户消息（任务目标）必须保留，丢了模型立刻走神");
        assertSame(messages.get(messages.size() - 1), result.get(result.size() - 1), "最后一条必须原样保留");
        assertTrue(compactor.estimateTokens(result) < before, "压缩后 token 必须下降");

        String digest = ((UserMessage) result.get(2)).singleText();
        assertTrue(digest.startsWith(RunLoopCompactor.DIGEST_MARKER), "第三条应是摘要");
        assertTrue(digest.contains("doc_read"), "摘要要保住「调过哪些工具」");
    }

    @Test
    @DisplayName("折叠后工具结果不会变成孤儿：保留段绝不以 ToolExecutionResultMessage 开头")
    void keepsToolCallAndResultPaired() {
        // keep-recent 设成偶数会正好切在「调用 / 结果」中间，这里刻意用奇数逼出该情形
        properties.getCompaction().setKeepRecent(5);

        List<ChatMessage> result = compactor.compact(longRun(12, 400), null);

        int digestIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof UserMessage um
                    && um.singleText().startsWith(RunLoopCompactor.DIGEST_MARKER)) {
                digestIndex = i;
            }
        }
        assertTrue(digestIndex > 0, "应生成摘要");
        assertFalse(result.get(digestIndex + 1) instanceof ToolExecutionResultMessage,
                "保留段打头的工具结果没有配对的 tool_calls，通道会直接 400");

        // 全量校验：每条工具结果前面都能找到发起它的 AiMessage
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof ToolExecutionResultMessage tr) {
                assertTrue(i > 0 && result.get(i - 1) instanceof AiMessage ai && ai.hasToolExecutionRequests()
                                && ai.toolExecutionRequests().get(0).id().equals(tr.id()),
                        "第 " + i + " 条工具结果失去了配对的调用");
            }
        }
    }

    @Test
    @DisplayName("二次压缩：上一版摘要并入新摘要，而不是当普通消息折掉")
    void mergesPreviousDigest() {
        List<ChatMessage> first = compactor.compact(longRun(12, 400), null);

        // 在已压缩的栈上继续跑若干轮，再压一次
        List<ChatMessage> grown = new ArrayList<>(first);
        for (int i = 100; i < 112; i++) {
            grown.add(toolCall("c" + i, "doc_replace_text", "{\"i\":" + i + "}"));
            grown.add(toolResult("c" + i, "doc_replace_text", filler(400)));
        }
        List<ChatMessage> second = compactor.compact(grown, null);

        String digest = second.stream()
                .filter(m -> m instanceof UserMessage um && um.singleText().startsWith(RunLoopCompactor.DIGEST_MARKER))
                .map(m -> ((UserMessage) m).singleText())
                .findFirst().orElseThrow();
        assertEquals(1, second.stream()
                .filter(m -> m instanceof UserMessage um && um.singleText().startsWith(RunLoopCompactor.DIGEST_MARKER))
                .count(), "摘要只能有一条，不能每压一次堆一条");
        assertTrue(digest.contains("doc_read"), "上一版摘要的内容要并进来，否则最早的事实被彻底丢干净");
        assertTrue(digest.contains("doc_replace_text"), "新折叠的中段也要在");
    }

    @Test
    @DisplayName("开关关掉即完全不压（行为回到加固前）")
    void disabledMeansNoOp() {
        properties.getCompaction().setEnabled(false);
        List<ChatMessage> messages = longRun(12, 400);

        assertSame(messages, compactor.compact(messages, null));
    }

    @Test
    @DisplayName("token 估算要算上工具调用参数与工具结果——runLoop 里这两类才是大头")
    void estimateCountsToolTraffic() {
        List<ChatMessage> messages = List.of(
                toolCall("c1", "doc_read", "{\"fileId\":123}"),
                toolResult("c1", "doc_read", filler(2000)));

        assertTrue(compactor.estimateTokens(messages) > 900,
                "漏算工具消息会让阈值永远不触发");
    }

    @Test
    @DisplayName("剪枝够用就不折叠：中段超长工具结果只留首尾，原文比摘要保得多")
    void pruningAloneSufficesWhenItShrinksEnough() {
        // 预算调大：阈值 6400 token。中段一条 20000 字符的工具结果剪成约 5.1k 后就够了
        when(compressor.getAvailableTokensForHistory(any())).thenReturn(8000);
        List<ChatMessage> messages = longRun(8, 400);
        messages.set(5, toolResult("c1", "doc_read", filler(20000)));
        int before = compactor.estimateTokens(messages);
        assertTrue(before > compactor.triggerThreshold(null), "用例前提：必须超阈值");

        List<ChatMessage> result = compactor.compact(messages, null);

        assertEquals(messages.size(), result.size(), "剪枝只改正文，不折叠任何消息");
        assertTrue(result.stream().noneMatch(m -> m instanceof UserMessage um
                        && um.singleText().startsWith(RunLoopCompactor.DIGEST_MARKER)),
                "剪枝够用时不该生成摘要");
        ToolExecutionResultMessage pruned = (ToolExecutionResultMessage) result.get(5);
        assertTrue(pruned.text().contains(RunLoopCompactor.PRUNE_MARKER), "中段要有省略标记");
        assertTrue(pruned.text().length() < 20000, "剪枝后必须变小");
        assertEquals("c1", pruned.id(), "id 必须原样保留，否则与 tool_calls 的配对断裂");
        assertEquals("doc_read", pruned.toolName());
        assertTrue(compactor.estimateTokens(result) <= compactor.triggerThreshold(null),
                "剪完应低于阈值（这正是跳过折叠的依据）");
    }

    @Test
    @DisplayName("最近的工具结果刻意不剪：模型正在引用它，剪了等于逼模型重调")
    void recentToolResultsAreNeverPruned() {
        List<ChatMessage> messages = longRun(12, 400);
        // 最后一轮的结果超长：位于 keepRecent 尾部区间，必须原样保留
        ChatMessage hugeTail = toolResult("c11", "doc_read", filler(20000));
        messages.set(messages.size() - 1, hugeTail);

        List<ChatMessage> result = compactor.compact(messages, null);

        assertSame(hugeTail, result.get(result.size() - 1), "尾部超长结果必须是原对象，不剪不动");
    }

    @Test
    @DisplayName("forceCompact：未超阈值也压（服务商已用 400 证实装不下，本地估算不作数）")
    void forceCompactBypassesThreshold() {
        // 预算调到远超栈大小：普通压缩绝不触发，但服务商回了 400 就得强压
        when(compressor.getAvailableTokensForHistory(any())).thenReturn(50000);
        List<ChatMessage> messages = longRun(6, 1000);
        int before = compactor.estimateTokens(messages);
        assertSame(messages, compactor.compact(messages, null), "用例前提：普通压缩不触发");

        List<ChatMessage> result = compactor.forceCompact(messages, null);

        assertTrue(result != messages, "强制压缩必须返回新实例（重试凭证 = 确实缩小了）");
        assertTrue(result.size() < messages.size());
        assertTrue(compactor.estimateTokens(result) < before, "强压后 token 必须下降");
        assertTrue(result.stream().anyMatch(m -> m instanceof UserMessage um
                && um.singleText().startsWith(RunLoopCompactor.DIGEST_MARKER)), "应生成摘要");
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) instanceof ToolExecutionResultMessage tr) {
                assertTrue(i > 0 && result.get(i - 1) instanceof AiMessage ai && ai.hasToolExecutionRequests()
                                && ai.toolExecutionRequests().get(0).id().equals(tr.id()),
                        "强制压缩也不许拆散工具配对（第 " + i + " 条）");
            }
        }
    }

    @Test
    @DisplayName("forceCompact 压不动就返回原实例：调用方据此放弃重试，避免原样重发再撞 400")
    void forceCompactReturnsSameInstanceWhenNothingToShrink() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("你好"),
                AiMessage.from("你好，有什么可以帮你的？")));

        assertSame(messages, compactor.forceCompact(messages, null),
                "中段不足、又没有可剪的工具结果：必须承认压不动");
    }

    @Test
    @DisplayName("折叠不缩小就放弃（必须变小）：小中段的摘要头开销会让栈反而变大")
    void foldThatWouldGrowIsAbandoned() {
        // 中段只有 4 条 100 字符级消息：摘要固定头 + 提示语比被折叠的内容还长
        List<ChatMessage> messages = longRun(6, 100);
        assertSame(messages, compactor.forceCompact(messages, null),
                "折叠后更大 = 净负资产，溢出恢复会拿着更大的栈白撞一次 400");
    }
}
