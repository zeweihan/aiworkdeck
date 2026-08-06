package com.checkba.service.ai;

import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级客户端能力过滤（Phase C）：ToolRegistry 三个消费点
 * （getAllSpecifications / execute / resolve）按会话能力过滤——
 * office 会话不见 doc_* 与 sheet_*，LOWA 会话不见 office_*，none 两者都不见。
 * 远端执行工具没有客户端执行器就是 30 秒超时死路径（PptxEditTools 教训）。
 */
class ToolRegistryCapabilityFilterTest {

    /** 覆盖三类前缀 + 普通后端工具的假工具集（名称避开真实工具） */
    static class CapFakeTools implements AgentToolComponent {
        @Tool("lowa doc tool")
        public String doc_cap_probe(@P("t") String t) { return "doc:" + t; }

        @Tool("lowa sheet tool")
        public String sheet_cap_probe(@P("t") String t) { return "sheet:" + t; }

        @Tool("office tool")
        public String office_cap_probe(@P("t") String t) { return "office:" + t; }

        @Tool("plain backend tool")
        public String plain_cap_probe(@P("t") String t) { return "plain:" + t; }
    }

    private ClientCapabilityService capabilities;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        capabilities = new ClientCapabilityService();
        registry = new ToolRegistry(List.of(new CapFakeTools()), new PluginService(), capabilities);
        registry.init();
    }

    private List<String> specNames(String conversationId) {
        return registry.getAllSpecifications(conversationId).stream().map(ToolSpecification::name).toList();
    }

    private ToolContext ctx(String conversationId) {
        return new ToolContext(1L, conversationId, 7L, "model-x");
    }

    @Test
    @DisplayName("office 会话：spec 不含 doc_*/sheet_*，execute 拒绝，resolve 不命中；office_* 正常")
    void officeSessionHidesLowaTools() {
        capabilities.record("conv-office", "office");

        List<String> names = specNames("conv-office");
        assertFalse(names.contains("doc_cap_probe"));
        assertFalse(names.contains("sheet_cap_probe"));
        assertTrue(names.contains("office_cap_probe"));
        assertTrue(names.contains("plain_cap_probe"));

        assertTrue(registry.resolve("doc_cap_probe", "conv-office").isEmpty());
        assertTrue(registry.resolve("sheet_cap_probe", "conv-office").isEmpty());
        assertTrue(registry.resolve("office_cap_probe", "conv-office").isPresent());

        ToolRegistry.ToolResult denied = registry.execute("doc_cap_probe", "{\"t\":\"x\"}", ctx("conv-office"));
        assertFalse(denied.found(), "office 会话 execute doc_* 应按未知工具拒绝");
        ToolRegistry.ToolResult ok = registry.execute("office_cap_probe", "{\"t\":\"x\"}", ctx("conv-office"));
        assertTrue(ok.found());
        assertTrue(ok.output().contains("office:x"));
    }

    @Test
    @DisplayName("lowa 会话：spec 不含 office_*，execute 拒绝，resolve 不命中；doc_*/sheet_* 正常")
    void lowaSessionHidesOfficeTools() {
        capabilities.record("conv-lowa", "lowa");

        List<String> names = specNames("conv-lowa");
        assertTrue(names.contains("doc_cap_probe"));
        assertTrue(names.contains("sheet_cap_probe"));
        assertFalse(names.contains("office_cap_probe"));

        assertTrue(registry.resolve("office_cap_probe", "conv-lowa").isEmpty());
        assertTrue(registry.resolve("doc_cap_probe", "conv-lowa").isPresent());

        ToolRegistry.ToolResult denied = registry.execute("office_cap_probe", "{\"t\":\"x\"}", ctx("conv-lowa"));
        assertFalse(denied.found());
        ToolRegistry.ToolResult ok = registry.execute("doc_cap_probe", "{\"t\":\"x\"}", ctx("conv-lowa"));
        assertTrue(ok.found());
    }

    @Test
    @DisplayName("none 会话：doc_*/sheet_*/office_* 全部隐藏，普通后端工具不受影响")
    void noneSessionHidesAllEditorTools() {
        capabilities.record("conv-none", "none");

        List<String> names = specNames("conv-none");
        assertFalse(names.contains("doc_cap_probe"));
        assertFalse(names.contains("sheet_cap_probe"));
        assertFalse(names.contains("office_cap_probe"));
        assertTrue(names.contains("plain_cap_probe"));

        assertFalse(registry.execute("doc_cap_probe", "{}", ctx("conv-none")).found());
        assertFalse(registry.execute("office_cap_probe", "{}", ctx("conv-none")).found());
        assertTrue(registry.execute("plain_cap_probe", "{\"t\":\"x\"}", ctx("conv-none")).found());
    }

    @Test
    @DisplayName("默认兼容现状：未声明能力（含 conversationId 为 null）按 lowa 处理")
    void unrecordedConversationDefaultsToLowa() {
        List<String> names = specNames("conv-never-recorded");
        assertTrue(names.contains("doc_cap_probe"), "未登记会话应保持现状（doc_* 可见）");
        assertFalse(names.contains("office_cap_probe"), "未登记会话不应看到 office_*");

        assertTrue(registry.resolve("doc_cap_probe", null).isPresent());
        assertTrue(registry.execute("doc_cap_probe", "{\"t\":\"x\"}", ctx(null)).found());
        assertFalse(registry.execute("office_cap_probe", "{}", ctx(null)).found());

        // 非法能力值按 lowa 兜底
        capabilities.record("conv-weird", "quantum");
        assertTrue(specNames("conv-weird").contains("doc_cap_probe"));
    }

    @Test
    @DisplayName("单参 resolve（无会话语境的元数据查询）不受能力过滤影响")
    void singleArgResolveUnfiltered() {
        capabilities.record("conv-office", "office");
        assertTrue(registry.resolve("doc_cap_probe").isPresent(),
                "无会话语境的 resolve 仍应命中（供展示名等元数据查询）");
    }
}
