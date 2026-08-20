package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.service.ai.tools.ToolFileGuard;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「一条超长工具结果就让整轮无法恢复」的两道防线。
 *
 * <p>病灶链：读取类工具的正文<b>没有上限</b>（extract_file_text 有 80k，read_file /
 * read_document 没有），一次 read_document 读一份几 MB 的合同就能产生几十万字符的单条
 * {@code ToolExecutionResultMessage}。下一次 generate 被服务商以上下文超限 400 挡回，
 * 编排器走被动恢复通道调 {@code forceCompact}——可这条超长结果落在 keepRecent
 * <b>尾区</b>（尾部平时刻意不剪），中段又不够 minMiddleMessages 条数，于是
 * forceCompact 恒返回原实例，编排器判定「压不动」直接终态。
 * 同一份文档每次重试都必然再撞同一个 400 = 这份文件永远读不了。
 *
 * <p>两道防线：① 工具侧截断（不让它长出来）；② forceCompact 兜底剪尾（长出来了也能救回来）。
 */
class OversizedToolResultRecoveryTest {

    private AiContextProperties properties;
    private RunLoopCompactor compactor;

    @BeforeEach
    void setUp() {
        properties = new AiContextProperties();
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.getAvailableTokensForHistory(any())).thenReturn(1000);
        compactor = new RunLoopCompactor(properties, compressor);
    }

    private static String filler(int chars) {
        return "合同条款".repeat(chars / 4);
    }

    /** 刚读完一份大文档的消息栈：中段为空，全部落在 keepRecent 尾区 */
    private static List<ChatMessage> justReadHugeDocument(int resultChars) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("system prompt"));
        messages.add(UserMessage.from("看看这份股权转让协议有什么风险"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("c0").name("read_document").arguments("{\"fileId\":\"12\"}").build()));
        messages.add(ToolExecutionResultMessage.from("c0", "read_document", filler(resultChars)));
        return messages;
    }

    @Test
    @DisplayName("防线二：尾部超长工具结果——强制压缩必须救得回来，不能判死")
    void forceCompactRecoversFromAnOversizedTailToolResult() {
        List<ChatMessage> messages = justReadHugeDocument(400_000);
        int before = compactor.estimateTokens(messages);

        List<ChatMessage> result = compactor.forceCompact(messages, null);

        assertNotSame(messages, result,
                "返回原实例 = 编排器判定「压不动」直接终态，这份文档就永远读不了了");
        assertTrue(compactor.estimateTokens(result) < before,
                "强压后 token 必须真的下降，否则重发再撞同一个 400");
        assertEquals(messages.size(), result.size(), "只剪正文，不许丢消息");

        ToolExecutionResultMessage pruned = (ToolExecutionResultMessage) result.get(3);
        assertEquals("c0", pruned.id(), "id 必须原样保留，否则工具调用与结果配对断裂 -> 通道 400");
        assertEquals("read_document", pruned.toolName(), "toolName 同样必须原样保留");
        assertTrue(pruned.text().contains("工具结果中段已省略"),
                "要留剪枝标记告诉模型中段没了，实际是：" + pruned.text().substring(0, 200));
    }

    @Test
    @DisplayName("普通压缩仍然不剪尾部：模型正在引用最近结果，既有取舍不变")
    void normalCompactionStillNeverPrunesTheTail() {
        List<ChatMessage> messages = justReadHugeDocument(400_000);
        ChatMessage hugeTail = messages.get(3);

        List<ChatMessage> result = compactor.compact(messages, null);

        assertSame(hugeTail, result.get(result.size() - 1),
                "非强制路径的尾部豁免是刻意设计，不许被这次修复带偏");
    }

    @Test
    @DisplayName("没有可剪的工具结果时，强制压缩仍然如实承认压不动")
    void forceCompactStillAdmitsDefeatWhenNothingIsPrunable() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("system prompt"),
                UserMessage.from("你好"),
                AiMessage.from("你好，有什么可以帮你的？")));

        assertSame(messages, compactor.forceCompact(messages, null),
                "压不动就得返回原实例，调用方据此放弃重试而不是无限重发");
    }

    @Test
    @DisplayName("防线一：读取类工具的正文上限是单一来源，超长时明确告知模型如何分段读")
    void toolTextIsCappedWithAnActionableNotice() {
        String huge = filler(400_000);
        String capped = ToolFileGuard.capToolText("股权转让协议.docx", huge);

        assertTrue(capped.length() < huge.length(), "必须真的截断");
        assertTrue(capped.contains("已截断"), "要告诉模型被截断了，实际开头是：" + capped.substring(0, 120));
        assertTrue(capped.contains("doc_read_paragraphs"),
                "要给模型下一步（分段读），否则它只会原地重试，实际开头是：" + capped.substring(0, 120));
        assertTrue(capped.length() <= ToolFileGuard.MAX_TOOL_TEXT_CHARS + 300,
                "截断后长度应贴近上限，实际 " + capped.length());
    }

    @Test
    @DisplayName("未超上限的正文原样返回，不加任何噪声")
    void shortToolTextIsUntouched() {
        String small = "第一条 转让标的";
        assertSame(small, ToolFileGuard.capToolText("a.docx", small));
    }
}
