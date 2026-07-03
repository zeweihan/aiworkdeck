package com.checkba.service.ai.mcp;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpResponseParser 单元测试：覆盖 PKULaw 网关线上出现过的三大类响应格式。
 *
 * fixture 说明：src/test/resources/mcp/ 下的样本按线上格式忠实重建
 * （SSE/StreamableHTTP、JSON 数组、JSON-RPC 对象、业务网关 code/data 封装），
 * 结构与旧版 McpClientService 逐分支处理的格式一一对应。
 */
class McpResponseParserTest {

    private String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/mcp/" + name)) {
            assertNotNull(in, "fixture not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Case 1: SSE / StreamableHTTP ---

    @Test
    @DisplayName("SSE 包裹 JSON-RPC content：抽取 text 内容")
    void sseWrappedJsonRpcContent() {
        String result = McpResponseParser.parse(fixture("sse-jsonrpc-content.txt"));
        assertEquals("《中华人民共和国民法典》第五百零九条：当事人应当按照约定全面履行自己的义务。"
                + "当事人应当遵循诚信原则，根据合同的性质、目的和交易习惯履行通知、协助、保密等义务。", result);
    }

    @Test
    @DisplayName("SSE 包裹业务网关 code/ok + data 数组：返回美化后的 data")
    void sseWrappedCodeOkArray() {
        String result = McpResponseParser.parse(fixture("sse-code-ok-array.txt"));
        JSONArray parsed = JSONUtil.parseArray(result);
        assertEquals(2, parsed.size());
        assertEquals("中华人民共和国公司法", parsed.getJSONObject(0).getStr("title"));
        assertEquals("第四条", parsed.getJSONObject(1).getStr("article"));
    }

    @Test
    @DisplayName("SSE 纯文本 data 行：过滤 [DONE] 后原样返回")
    void ssePlainText() {
        String result = McpResponseParser.parse(fixture("sse-plain-text.txt"));
        assertEquals("未识别到法律条文引用", result);
    }

    // --- Case 2: JSON 数组 ---

    @Test
    @DisplayName("JSON 数组：整体美化输出，内容不丢失")
    void jsonArray() {
        String result = McpResponseParser.parse(fixture("json-array.json"));
        JSONArray parsed = JSONUtil.parseArray(result);
        assertEquals(2, parsed.size());
        assertEquals("第五百零九条", parsed.getJSONObject(0).getStr("articleNo"));
        assertEquals("违约责任", parsed.getJSONObject(1).getStr("hitReason"));
    }

    // --- Case 3: JSON 对象 ---

    @Test
    @DisplayName("JSON-RPC result.content：只拼接 type=text 的条目，忽略其它类型")
    void jsonRpcContentTextOnly() {
        String result = McpResponseParser.parse(fixture("jsonrpc-object-content.json"));
        assertEquals("《中华人民共和国刑法》第二百六十六条："
                + "诈骗公私财物，数额较大的，处三年以下有期徒刑、拘役或者管制，并处或者单处罚金。", result);
        assertFalse(result.contains("pkulaw://"), "非 text 条目不应出现在结果里");
    }

    @Test
    @DisplayName("JSON-RPC result 无 content：返回 result 对象本身")
    void jsonRpcResultWithoutContent() {
        String result = McpResponseParser.parse(fixture("jsonrpc-object-result-plain.json"));
        JSONObject parsed = JSONUtil.parseObj(result);
        assertEquals(0, (int) parsed.getInt("total"));
        assertEquals("未检索到匹配法条", parsed.getStr("message"));
    }

    @Test
    @DisplayName("业务网关 code/ok + data 对象：返回美化后的 data")
    void codeOkDataObject() {
        String result = McpResponseParser.parse(fixture("code-ok-object.json"));
        JSONObject parsed = JSONUtil.parseObj(result);
        assertEquals("中华人民共和国劳动合同法", parsed.getStr("lawTitle"));
        assertEquals("第十条", parsed.getStr("articleNo"));
    }

    @Test
    @DisplayName("JSON-RPC error：返回 Error from MCP server 前缀的描述")
    void jsonRpcError() {
        String result = McpResponseParser.parse(fixture("error-object.json"));
        assertTrue(result.startsWith("Error from MCP server: "), "actual: " + result);
        assertTrue(result.contains("Method not found"));
    }

    @Test
    @DisplayName("普通 JSON 对象（无 code/result/error）：整体美化输出")
    void plainObjectFallback() {
        String result = McpResponseParser.parse("{\"foo\":\"bar\",\"count\":1}");
        JSONObject parsed = JSONUtil.parseObj(result);
        assertEquals("bar", parsed.getStr("foo"));
        assertEquals(1, (int) parsed.getInt("count"));
    }

    // --- Case 4: 未知格式 ---

    @Test
    @DisplayName("未知格式：去除首尾空白后原样返回")
    void unknownFormatPassthrough() {
        assertEquals("plain text response", McpResponseParser.parse("  plain text response \n"));
    }
}
