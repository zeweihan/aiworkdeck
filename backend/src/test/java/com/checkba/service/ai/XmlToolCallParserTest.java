package com.checkba.service.ai;

import com.checkba.service.ai.tools.AgentToolComponent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XmlToolCallParser 单元测试：XML 兜底协议的各种历史容错格式。
 */
class XmlToolCallParserTest {

    /**
     * 用与生产同名的工具签名做解析目标
     */
    static class ProtocolFakeTools implements AgentToolComponent {

        @Tool("web search")
        public String search_web(@P("query") String query) {
            return "";
        }

        @Tool("run python")
        public String run_python(@P("code") String code) {
            return "";
        }

        @Tool("write docx")
        public String write_docx(@P("name") String fileName, @P("md") String markdownContent, Long projectId) {
            return "";
        }

        @Tool("find replace")
        public String doc_find_replace(@P("f") String findText, @P("r") String replaceText, @P("all") Boolean replaceAll) {
            return "";
        }

        @Tool("gen ppt")
        public String pptx_generate(@P("topic") String topic, Long projectId, @P("file") String fileName) {
            return "";
        }

        @Tool("gen ppt outline")
        public String pptx_generate_outline(@P("topic") String topic, @P("lang") String language) {
            return "";
        }
    }

    private XmlToolCallParser parser;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry(List.of(new ProtocolFakeTools()), new PluginService());
        registry.init();
        parser = new XmlToolCallParser(registry);
    }

    private XmlToolCallParser.ParsedCall single(String content) {
        List<XmlToolCallParser.ParsedCall> calls = parser.parse(content);
        assertEquals(1, calls.size(), "expected exactly one parsed call");
        return calls.get(0);
    }

    @Test
    @DisplayName("基本：key=\"value\" 形式")
    void parsesSimpleCall() {
        XmlToolCallParser.ParsedCall call = single("<tool_code>search_web(query=\"公司法第16条\")</tool_code>");
        assertEquals("search_web", call.toolName());
        assertEquals("公司法第16条", cn.hutool.json.JSONUtil.parseObj(call.argsJson()).getStr("query"));
    }

    @Test
    @DisplayName("run_python：多行代码含其他工具名不被误匹配")
    void parsesMultilinePython() {
        String content = "<tool_code>run_python(code='import pandas\\n# search_web(query=\"x\") 是注释\\nprint(1)')</tool_code>";
        XmlToolCallParser.ParsedCall call = single(content);
        assertEquals("run_python", call.toolName());
        String code = cn.hutool.json.JSONUtil.parseObj(call.argsJson()).getStr("code");
        assertTrue(code.contains("import pandas"));
        assertTrue(code.contains("print(1)"));
        assertTrue(code.contains("\n"), "escaped newlines should be unescaped");
    }

    @Test
    @DisplayName("三引号多行 + 参数别名 name→fileName")
    void parsesTripleQuotedAndAlias() {
        String content = "<tool_code>write_docx(name=\"备忘录.docx\", markdown_content=\"\"\"# 标题\n第一行\n第二行\"\"\")</tool_code>";
        XmlToolCallParser.ParsedCall call = single(content);
        assertEquals("write_docx", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("备忘录.docx", args.getStr("fileName"));
        assertTrue(args.getStr("markdownContent").contains("第二行"));
    }

    @Test
    @DisplayName("JSON 风格：tool({\"key\":\"value\"})")
    void parsesJsonStyleCall() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>doc_find_replace({\"findText\":\"甲方\",\"replaceText\":\"乙方\"})</tool_code>");
        assertEquals("doc_find_replace", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("甲方", args.getStr("findText"));
        assertEquals("乙方", args.getStr("replaceText"));
    }

    @Test
    @DisplayName("ctrl46 定界符 + 无括号写法（Gemini 兼容）")
    void parsesCtrl46Format() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>pptx_generate_outline{language:<ctrl46>zh<ctrl46>,topic:<ctrl46>法律AI应用<ctrl46>}</tool_code>");
        assertEquals("pptx_generate_outline", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("zh", args.getStr("language"));
        assertEquals("法律AI应用", args.getStr("topic"));
    }

    @Test
    @DisplayName("最长名优先：pptx_generate_outline 不被 pptx_generate 抢占")
    void longestNameWins() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>print(pptx_generate_outline(topic=\"年度总结\", language=\"zh\"))</tool_code>");
        assertEquals("pptx_generate_outline", call.toolName());
    }

    @Test
    @DisplayName("工具名别名：search_laws → 保留原名交由注册表映射")
    void resolvesAliasName() {
        XmlToolCallParser.ParsedCall call = single("<tool_code>search_laws(query=\"公司法\")</tool_code>");
        assertEquals("search_laws", call.toolName());
        assertEquals("公司法", cn.hutool.json.JSONUtil.parseObj(call.argsJson()).getStr("query"));
    }

    @Test
    @DisplayName("灰度更名：旧名 wps_find_replace(...) 经别名解析命中 doc_find_replace")
    void resolvesLegacyWpsNameViaAlias() {
        // 老对话历史 / 模型惯性输出的旧名：参数按 doc_find_replace 的签名正确提取
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>wps_find_replace(findText=\"甲方\", replaceText=\"乙方\", replaceAll=true)</tool_code>");
        assertEquals("wps_find_replace", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("甲方", args.getStr("findText"));
        assertEquals("乙方", args.getStr("replaceText"));
        // 分发层按别名表映射到 doc_find_replace
        assertEquals("doc_find_replace", ToolRegistry.TOOL_NAME_ALIASES.get(call.toolName()));
    }

    @Test
    @DisplayName("<code> 标签与多个调用")
    void parsesMultipleCallsAndCodeTag() {
        String content = "<code>search_web(query=\"a\")</code>\n中间文本\n<tool_code>search_web(query=\"b\")</tool_code>";
        List<XmlToolCallParser.ParsedCall> calls = parser.parse(content);
        assertEquals(2, calls.size());
        assertEquals("a", cn.hutool.json.JSONUtil.parseObj(calls.get(0).argsJson()).getStr("query"));
        assertEquals("b", cn.hutool.json.JSONUtil.parseObj(calls.get(1).argsJson()).getStr("query"));
    }

    @Test
    @DisplayName("process name 提取")
    void extractsProcessName() {
        assertEquals("搜索法规",
                parser.extractProcessName("<process name=\"搜索法规\"><tool_code>x()</tool_code></process>").orElse(null));
        assertTrue(parser.extractProcessName("no process here").isEmpty());
    }

    @Test
    @DisplayName("转义字符：\\n \\\" 正确还原")
    void handlesEscapes() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>search_web(query=\"第一行\\n带\\\"引号\\\"\")</tool_code>");
        String q = cn.hutool.json.JSONUtil.parseObj(call.argsJson()).getStr("query");
        assertEquals("第一行\n带\"引号\"", q);
    }

    @Test
    @DisplayName("containsToolCall 判定")
    void detectsToolCallPresence() {
        assertTrue(parser.containsToolCall("<tool_code>x()</tool_code>"));
        assertTrue(parser.containsToolCall("<code>x()</code>"));
        assertFalse(parser.containsToolCall("纯文本回答"));
        assertFalse(parser.containsToolCall(null));
    }
}
