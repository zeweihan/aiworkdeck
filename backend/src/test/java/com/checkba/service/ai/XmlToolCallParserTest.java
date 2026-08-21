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
        ToolRegistry registry = new ToolRegistry(List.of(new ProtocolFakeTools()), new PluginService(), new ClientCapabilityService());
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

    /**
     * 参数值里出现一对 {@code ({ ... })} 是常态（正文里引用代码、字典字面量、
     * 甚至一句「helper({k: 1})」）。此前 tryExtractJsonObjectArgs 用
     * indexOf("({") / lastIndexOf("})") 在整段文本里找，命中即把中间那段当成
     * 整体参数对象返回，真正的命名参数全部丢失——工具拿到一组凭空捏造的参数
     * 却照常执行，而且不报错。
     */
    @Test
    @DisplayName("参数值里的 ({...}) 不得被当成 JSON 风格整体参数")
    void codeLikeArgumentIsNotMistakenForJsonStyleCall() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>doc_find_replace(findText=\"甲方\", replaceText=\"见 helper({k: 1}) 的返回\")</tool_code>");
        assertEquals("doc_find_replace", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("甲方", args.getStr("findText"), "真实参数不该被 ({...}) 顶掉: " + call.argsJson());
        assertTrue(args.getStr("replaceText") != null && args.getStr("replaceText").contains("helper("),
                "replaceText 应保留原文: " + call.argsJson());
    }

    /**
     * 协议是「一个 tool_code 块放一个调用，要批量就连续输出多个块」
     * （system_prompt.md「无需中间判断的调用必须在同一轮批量输出」）。
     * 模型偶尔会把两条塞进同一个块，而 parse() 每个块只产出一个 ParsedCall、
     * extractStringArg 又只取每个参数名的第一次出现——第二条调用连痕迹都不留：
     * 没有 ParsedCall、没有报错、没有日志，模型看到第一条成功就当整件事做完了，
     * 用户要求的第二处修改根本没发生。
     */
    @Test
    @DisplayName("同一个 tool_code 块里的两条调用都要被解析出来")
    void twoStatementsInOneBlockAreBothParsed() {
        List<XmlToolCallParser.ParsedCall> calls = parser.parse(
                "<tool_code>doc_find_replace(findText=\"甲\", replaceText=\"乙\")\n"
                        + "doc_find_replace(findText=\"丙\", replaceText=\"丁\")</tool_code>");
        assertEquals(2, calls.size(), "第二条调用被静默丢掉了: " + calls);
        assertEquals("甲", cn.hutool.json.JSONUtil.parseObj(calls.get(0).argsJson()).getStr("findText"));
        assertEquals("丙", cn.hutool.json.JSONUtil.parseObj(calls.get(1).argsJson()).getStr("findText"));
    }

    /** 护栏：看不明白就别拆——拆错了会凭空多执行一个调用，比少执行一个更糟。 */
    @Test
    @DisplayName("括号不配平/引号没闭合/尾部有残留时一律退回单条解析")
    void ambiguousBlocksFallBackToSingleParse() {
        assertEquals(1, XmlToolCallParser.splitStatements(
                "doc_find_replace(findText=\"甲\") doc_find_replace(findText=\"丙\"").size(), "引号没闭合");
        assertEquals(1, XmlToolCallParser.splitStatements(
                "doc_find_replace(findText=\"甲\") 然后再来一次 doc_find_replace(findText=\"丙\") 说明文字").size(),
                "最后一条之后还有残留文字");
        assertEquals(1, XmlToolCallParser.splitStatements(
                "run_python(code=\"foo()\nbar()\")").size(), "run_python 的 code 参数一律不拆");
        // 单条调用（含参数值里的括号）保持不拆
        assertEquals(1, XmlToolCallParser.splitStatements(
                "doc_find_replace(findText=\"甲\", replaceText=\"见 helper({k: 1}) 的返回\")").size());
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
    @DisplayName("位置参数：doc_find_replace(\"合同\", \"协议\", true) 按签名顺序映射")
    void parsesPositionalArgs() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>doc_find_replace(\"合同\", \"协议\", true)</tool_code>");
        assertEquals("doc_find_replace", call.toolName());
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("合同", args.getStr("findText"));
        assertEquals("协议", args.getStr("replaceText"));
        assertEquals("true", args.getStr("replaceAll"));
    }

    @Test
    @DisplayName("位置参数：跳过服务端注入参数 projectId 不错位")
    void positionalArgsSkipServerContextParams() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>pptx_generate(\"年度总结\", \"汇报.pptx\")</tool_code>");
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("年度总结", args.getStr("topic"));
        assertEquals("汇报.pptx", args.getStr("fileName"));
        assertFalse(args.containsKey("projectId"));
    }

    @Test
    @DisplayName("混合写法：位置参数与命名参数共存")
    void parsesMixedPositionalAndNamedArgs() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>doc_find_replace(\"甲方\", replaceText=\"乙方\")</tool_code>");
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("甲方", args.getStr("findText"));
        assertEquals("乙方", args.getStr("replaceText"));
    }

    @Test
    @DisplayName("位置参数：引号内的逗号与转义不被切分")
    void positionalArgsKeepQuotedCommas() {
        XmlToolCallParser.ParsedCall call = single(
                "<tool_code>doc_find_replace(\"甲方, 乙方\", \"双方\\n各方\")</tool_code>");
        cn.hutool.json.JSONObject args = cn.hutool.json.JSONUtil.parseObj(call.argsJson());
        assertEquals("甲方, 乙方", args.getStr("findText"));
        assertEquals("双方\n各方", args.getStr("replaceText"));
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
