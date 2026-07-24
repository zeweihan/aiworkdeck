package com.checkba.service.ai;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolContext;
import com.checkba.service.ai.tools.ToolMeta;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试：注册、分发、参数绑定、上下文注入、别名与遗留默认值。
 */
class ToolRegistryTest {

    /**
     * 测试用工具组件，覆盖各种参数绑定场景
     */
    static class FakeTools implements AgentToolComponent {

        @Tool("Echo the text back")
        @ToolMeta(displayName = "回声", category = "test", fileEffect = "ADDED", fileArg = "fileName")
        public String echo(@P("text to echo") String text) {
            return "echo:" + text;
        }

        @Tool("Report injected server context")
        public String context_probe(@P("marker") String marker, Long projectId, String conversationId, Long userId) {
            return projectId + "|" + conversationId + "|" + userId + "|" + marker;
        }

        @Tool("Write a docx file (alias binding test)")
        public String write_docx(@P("file name") String fileName, @P("markdown") String markdownContent, Long projectId) {
            return fileName + "::" + markdownContent + "::" + projectId;
        }

        @Tool("Numeric conversion test")
        public String numbers(@P("an int") Integer a, @P("a long") Long b, @P("a bool") boolean c) {
            return a + "/" + b + "/" + c;
        }

        @Tool("Model id passthrough test")
        public String model_probe(@P("model") String modelId) {
            return "model:" + modelId;
        }

        @Tool("Find replace legacy default test")
        public String doc_find_replace(@P("find") String findText, @P("replace") String replaceText, @P("all") Boolean replaceAll) {
            return findText + ">" + replaceText + ":" + replaceAll;
        }

        @Tool("ThreadLocal holder population test")
        public String holder_probe() {
            return "holder:" + ProjectContextHolder.getProjectIdAsLong() + "|" + ProjectContextHolder.getUserId();
        }
    }

    private ToolRegistry registry;
    private final ToolContext ctx = new ToolContext(5L, "conv-1", 7L, "google/gemini-2.5-pro");

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of(new FakeTools()), new PluginService());
        registry.init();
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    @Test
    @DisplayName("注册：@Tool 方法全部进入规格列表")
    void registersAllTools() {
        assertEquals(7, registry.getAllSpecifications().size());
        assertTrue(registry.hasTool("echo"));
        assertTrue(registry.hasTool("doc_find_replace"));
        assertFalse(registry.hasTool("nonexistent"));
    }

    @Test
    @DisplayName("分发：普通字符串参数")
    void executesSimpleTool() {
        ToolRegistry.ToolResult r = registry.execute("echo", "{\"text\":\"hi\"}", ctx);
        assertTrue(r.found());
        assertEquals("echo:hi", r.output());
        assertEquals("回声", r.tool().displayName());
        assertEquals("ADDED", r.tool().meta().fileEffect());
    }

    @Test
    @DisplayName("安全：projectId/conversationId/userId 强制来自服务端上下文，LLM 传值被忽略")
    void serverContextOverridesLlmArgs() {
        ToolRegistry.ToolResult r = registry.execute("context_probe",
                "{\"marker\":\"m\",\"projectId\":999,\"conversationId\":\"hacked\",\"userId\":666}", ctx);
        assertEquals("5|conv-1|7|m", r.output());
    }

    @Test
    @DisplayName("兼容：历史参数别名 name→fileName, markdown_content→markdownContent")
    void bindsAliasedArgs() {
        ToolRegistry.ToolResult r = registry.execute("write_docx",
                "{\"name\":\"a.docx\",\"markdown_content\":\"# 标题\"}", ctx);
        assertEquals("a.docx::# 标题::5", r.output());
    }

    @Test
    @DisplayName("容错：数字字符串与 'null'/'None' 归一化")
    void convertsNumericAndNullStrings() {
        ToolRegistry.ToolResult r = registry.execute("numbers",
                "{\"a\":\"3\",\"b\":\"null\",\"c\":\"true\"}", ctx);
        assertEquals("3/null/true", r.output());
    }

    @Test
    @DisplayName("modelId：LLM 未显式传参时回落到会话所选模型")
    void modelIdFallsBackToContext() {
        ToolRegistry.ToolResult r = registry.execute("model_probe", "{}", ctx);
        assertEquals("model:google/gemini-2.5-pro", r.output());

        ToolRegistry.ToolResult explicit = registry.execute("model_probe",
                "{\"modelId\":\"openai/gpt-4o\"}", ctx);
        assertEquals("model:openai/gpt-4o", explicit.output());
    }

    @Test
    @DisplayName("行为保持：doc_find_replace 缺省 replaceAll=true")
    void appliesLegacyDefaults() {
        ToolRegistry.ToolResult r = registry.execute("doc_find_replace",
                "{\"findText\":\"甲\",\"replaceText\":\"乙\"}", ctx);
        assertEquals("甲>乙:true", r.output());
    }

    @Test
    @DisplayName("修复：工具执行线程的 ProjectContextHolder 由注册表装填")
    void populatesThreadLocalHolder() {
        ProjectContextHolder.clear();
        ToolRegistry.ToolResult r = registry.execute("holder_probe", "{}", ctx);
        assertEquals("holder:5|7", r.output());
    }

    @Test
    @DisplayName("未知工具：found=false 且返回统一提示")
    void unknownToolNotFound() {
        ToolRegistry.ToolResult r = registry.execute("no_such_tool", "{}", ctx);
        assertFalse(r.found());
        assertFalse(r.success());
        assertEquals("Tool not found or arguments invalid.", r.output());
    }

    @Test
    @DisplayName("别名：search_laws 等工具名别名可分发")
    void resolvesToolNameAlias() {
        // search_laws → search_web 不在 FakeTools 中，验证别名解析不误报 found
        ToolRegistry.ToolResult r = registry.execute("search_laws", "{\"query\":\"q\"}", ctx);
        assertFalse(r.found());
    }

    @Test
    @DisplayName("灰度到期：wps_* 旧名已移除，不再分发（0.7.9 后清理）")
    void legacyWpsNamesNoLongerDispatch() {
        ToolRegistry.ToolResult r = registry.execute("wps_find_replace",
                "{\"findText\":\"甲\",\"replaceText\":\"乙\"}", ctx);
        assertFalse(r.found(), "wps_* 旧名不应再命中任何工具");
        ToolRegistry.TOOL_NAME_ALIASES.keySet().forEach(alias ->
                assertFalse(alias.startsWith("wps_"), "别名表不应再含 wps_* 条目：" + alias));
    }

    // ==== 插件启停过滤 + 权限校验（Phase 3A） ====

    /** 模拟插件 JAR 中的工具类 */
    static class FakePluginTools {
        @Tool("Plugin echo tool")
        public String plugin_echo(@P("text to echo") String text) {
            return "plugin:" + text;
        }
    }

    /** 构造带一个已注册插件工具（plugin_echo）的 PluginService */
    private PluginService pluginServiceWith(String pluginId, List<String> declaredPermissions,
                                            List<String> toolRequiredPermissions) {
        PluginService ps = new PluginService();
        PluginService.PluginMetadata meta = new PluginService.PluginMetadata();
        meta.setId(pluginId);
        meta.setName(pluginId);
        meta.setPermissions(declaredPermissions);
        PluginService.PluginToolInfo info = new PluginService.PluginToolInfo();
        info.setName("plugin_echo");
        info.setPermissions(toolRequiredPermissions);
        meta.setTools(List.of(info));
        ps.getPlugins().add(meta);
        ps.registerToolObject(new FakePluginTools(), pluginId);
        return ps;
    }

    @Test
    @DisplayName("启停：禁用插件后规格/名单/分发全部不可见，重新启用即恢复，内置工具不受影响")
    void disabledPluginToolsAreHidden() {
        PluginService ps = pluginServiceWith("my-plugin", List.of("network"), null);
        ToolRegistry reg = new ToolRegistry(List.of(new FakeTools()), ps);
        reg.init();

        // 启用态：插件工具可见可分发
        assertTrue(reg.getAllSpecifications().stream().anyMatch(s -> s.name().equals("plugin_echo")));
        assertTrue(reg.toolNamesLongestFirst().contains("plugin_echo"));
        assertEquals("plugin:hi", reg.execute("plugin_echo", "{\"text\":\"hi\"}", ctx).output());

        ps.setEnabled("my-plugin", false);

        assertFalse(reg.getAllSpecifications().stream().anyMatch(s -> s.name().equals("plugin_echo")),
                "禁用后 LLM 不应看到插件工具规格");
        assertFalse(reg.toolNamesLongestFirst().contains("plugin_echo"));
        assertFalse(reg.hasTool("plugin_echo"));
        ToolRegistry.ToolResult r = reg.execute("plugin_echo", "{\"text\":\"hi\"}", ctx);
        assertFalse(r.found(), "禁用后分发应返回 not found");
        // 内置工具不受插件启停影响
        assertTrue(reg.hasTool("echo"));
        assertEquals("echo:hi", reg.execute("echo", "{\"text\":\"hi\"}", ctx).output());

        ps.setEnabled("my-plugin", true);

        assertTrue(reg.getAllSpecifications().stream().anyMatch(s -> s.name().equals("plugin_echo")),
                "重新启用后应恢复可见");
        assertEquals("plugin:hi", reg.execute("plugin_echo", "{\"text\":\"hi\"}", ctx).output());
    }

    @Test
    @DisplayName("权限：工具所需权限未在 manifest permissions 声明时分发被拒绝")
    void undeclaredPermissionIsRejected() {
        PluginService ps = pluginServiceWith("my-plugin", List.of("file_read"), List.of("network"));
        ToolRegistry reg = new ToolRegistry(List.of(new FakeTools()), ps);
        reg.init();

        ToolRegistry.ToolResult r = reg.execute("plugin_echo", "{\"text\":\"hi\"}", ctx);
        assertTrue(r.found(), "工具存在，只是被权限拒绝");
        assertFalse(r.success());
        assertTrue(r.output().startsWith("Error: permission denied"), "应返回明确的权限拒绝错误: " + r.output());
        assertTrue(r.output().contains("network"), "错误信息应指明缺失的权限: " + r.output());
    }

    @Test
    @DisplayName("权限：所需权限已声明时正常执行；未声明所需权限的工具（v1 兼容）不受影响")
    void declaredPermissionAllowsExecution() {
        PluginService declared = pluginServiceWith("p1", List.of("network"), List.of("network"));
        ToolRegistry reg1 = new ToolRegistry(List.of(new FakeTools()), declared);
        reg1.init();
        assertEquals("plugin:ok", reg1.execute("plugin_echo", "{\"text\":\"ok\"}", ctx).output());

        PluginService legacy = pluginServiceWith("p2", null, null);
        ToolRegistry reg2 = new ToolRegistry(List.of(new FakeTools()), legacy);
        reg2.init();
        assertEquals("plugin:ok", reg2.execute("plugin_echo", "{\"text\":\"ok\"}", ctx).output());
    }
}
