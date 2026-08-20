package com.checkba.service.ai.mcp;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 StreamableHTTP 流（多事件、带 event: 行）必须解析出正文。
 *
 * <p>两处病灶都会让「查法条」拿不到内容且零错误信号：
 *
 * <p><b>一、判据认不出 SSE。</b>旧判据是「正文第一行以 data: 开头」，而真实的
 * MCP StreamableHTTP 流第一行往往是 {@code event: message}，于是整段带
 * {@code event:} / 空行的协议原文顺着「未知格式」分支原样交给模型。
 *
 * <p><b>二、多事件被无分隔符拼在一起。</b>一条正常的 MCP 流是「若干条
 * notifications/progress + 一条真正的 result」，拼完是 <code>{...}{...}</code>
 * 这种非法 JSON——而 hutool 的 {@code JSONUtil.parseObj} 对它**不报错**，
 * 只解析第一个对象、丢掉其余（本用例把这条 hutool 行为也钉住了）。
 * 模型于是收到一条进度通知当作工具结果。
 */
class McpSseMultiEventTest {

    private static final String LAW_TEXT =
            "《中华人民共和国民法典》第五百七十七条：当事人一方不履行合同义务或者履行合同义务不符合约定的，"
                    + "应当承担继续履行、采取补救措施或者赔偿损失等违约责任。";

    private static String resultEvent() {
        return "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"content\":"
                + "[{\"type\":\"text\",\"text\":\"" + LAW_TEXT + "\"}]}}";
    }

    private static String progressEvent(int n) {
        return "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                + "\"params\":{\"progress\":" + n + ",\"total\":3}}";
    }

    @Test
    @DisplayName("以 event: 开头的流也要认出是 SSE，不能把协议原文丢给模型")
    void streamStartingWithEventLineIsRecognised() {
        String sse = String.join("\n",
                "event: message",
                resultEvent(),
                "");

        String out = McpResponseParser.parse(sse);

        assertEquals(LAW_TEXT, out, "第一行是 event: 时旧判据认不出 SSE，整段协议原文会被当成答案");
        assertFalse(out.contains("event:"), "协议行不许漏给模型：" + out);
        assertFalse(out.contains("data:"), "协议行不许漏给模型：" + out);
    }

    @Test
    @DisplayName("进度事件 + 结果事件：必须取结果，不能取到进度通知")
    void progressEventsAreSkippedInFavourOfTheActualResult() {
        String sse = String.join("\n",
                "event: message",
                progressEvent(1),
                "",
                "event: message",
                progressEvent(2),
                "",
                "event: message",
                resultEvent(),
                "");

        String out = McpResponseParser.parse(sse);

        assertEquals(LAW_TEXT, out, "旧实现拼接后被 hutool 只认头一个对象，模型拿到的是进度通知");
        assertFalse(out.contains("notifications/progress"), "进度事件不许当成结果：" + out);
    }

    @Test
    @DisplayName("结果事件在前、进度事件在后，同样要取结果")
    void resultIsFoundEvenWhenItIsNotTheLastEvent() {
        String sse = String.join("\n",
                "event: message",
                resultEvent(),
                "",
                "event: message",
                progressEvent(3),
                "");

        assertEquals(LAW_TEXT, McpResponseParser.parse(sse));
    }

    @Test
    @DisplayName("JSON-RPC 错误事件要被识别成错误，不被进度通知盖过")
    void errorEventIsSurfaced() {
        String sse = String.join("\n",
                "event: message",
                progressEvent(1),
                "",
                "event: message",
                "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32603,\"message\":\"upstream timeout\"}}",
                "");

        String out = McpResponseParser.parse(sse);

        assertTrue(out.startsWith("Error from MCP server"), "实际是：" + out);
        assertTrue(out.contains("upstream timeout"), "要带上真实原因，实际是：" + out);
    }

    @Test
    @DisplayName("同一事件里的多条 data 行按规范用换行拼接（多行 JSON 载荷唯一正确的拼法）")
    void multipleDataLinesWithinOneEventAreJoinedByNewline() {
        String sse = String.join("\n",
                "event: message",
                "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"content\":",
                "data: [{\"type\":\"text\",\"text\":\"分两行发的正文\"}]}}",
                "");

        assertEquals("分两行发的正文", McpResponseParser.parse(sse));
    }

    @Test
    @DisplayName("钉住 hutool 的宽松解析：拼接出来的非法 JSON 它不报错，只认头一个对象")
    void hutoolSilentlyAcceptsConcatenatedObjects() {
        // 这条不是在测我们的代码，而是把「为什么绝不能无分隔符拼 data 行」钉在用例里：
        // 换掉 JSON 库或升级 hutool 后若这条变红，说明这个前提变了，值得重新审视上面的实现。
        assertEquals(1, JSONUtil.parseObj("{\"a\":1}{\"b\":2}").getInt("a"));
        assertFalse(JSONUtil.parseObj("{\"a\":1}{\"b\":2}").containsKey("b"),
                "hutool 会静默丢掉后面的对象——正是它让拼接错误无声无息");
    }

    @Test
    @DisplayName("单事件与纯文本流的既有行为不变")
    void singleEventAndPlainTextBehaviourUnchanged() {
        assertEquals(LAW_TEXT, McpResponseParser.parse(resultEvent()));
        assertEquals("未识别到法律条文引用",
                McpResponseParser.parse("data: 未识别到法律条文引用\ndata: [DONE]\n"));
    }
}
