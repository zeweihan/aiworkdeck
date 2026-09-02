package com.checkba.service.ai;

import com.checkba.service.ai.tools.AgentToolComponent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具组件自报不可用时不下发给模型（dev-board#396）。
 *
 * <p>病灶：run_python 无条件依赖本机 Docker，而绝大多数用户机器上没有 Docker。
 * 模型读不出一张图片时把它当成「另找一条 OCR 路子」调用，拿到
 * 「Cannot run program docker」后自己得出「OCR 环境（docker）不可用」的结论转告用户——
 * 而图片本来就能读。看不见的工具不会被走上去。
 */
class ToolRegistryAvailabilityTest {

    static class UnavailableTools implements AgentToolComponent {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Tool("probe tool that should stay hidden")
        public String hidden_avail_probe(@P("t") String t) {
            return "hidden:" + t;
        }
    }

    static class AvailableTools implements AgentToolComponent {
        @Tool("probe tool that stays visible")
        public String visible_avail_probe(@P("t") String t) {
            return "visible:" + t;
        }
    }

    /** isAvailable() 抛异常不许掀翻启动——最坏就是照旧下发。 */
    static class ThrowingTools implements AgentToolComponent {
        @Override
        public boolean isAvailable() {
            throw new IllegalStateException("probe blew up");
        }

        @Tool("probe tool whose availability check throws")
        public String throwing_avail_probe(@P("t") String t) {
            return "throwing:" + t;
        }
    }

    private static ToolRegistry registryOf(AgentToolComponent... components) {
        ToolRegistry registry =
                new ToolRegistry(List.of(components), new PluginService(), new ClientCapabilityService());
        registry.init();
        return registry;
    }

    private static List<String> specNames(ToolRegistry registry) {
        return registry.getAllSpecifications().stream().map(ToolSpecification::name).toList();
    }

    @Test
    @DisplayName("isAvailable()=false 的组件不进 spec，=true 的照常进")
    void unavailableComponentIsNotOfferedToTheModel() {
        ToolRegistry registry = registryOf(new UnavailableTools(), new AvailableTools());

        List<String> names = specNames(registry);
        assertFalse(names.contains("hidden_avail_probe"), "不可用的组件不该出现在给模型的清单里：" + names);
        assertTrue(names.contains("visible_avail_probe"), "可用的组件必须照常下发：" + names);
    }

    @Test
    @DisplayName("不下发不等于注销：真被调到时仍然走工具自己那句可行动的错误")
    void hiddenToolIsStillRegisteredSoItsOwnErrorWins() {
        ToolRegistry registry = registryOf(new UnavailableTools());

        assertTrue(registry.resolve("hidden_avail_probe").isPresent(),
                "登记要保留：XML 兜底路径调到时，'tool not found' 远不如工具自己的说明有用");
    }

    @Test
    @DisplayName("探测抛异常按可用处理，后端照常起得来")
    void throwingProbeDoesNotBreakStartup() {
        List<String> names = specNames(registryOf(new ThrowingTools()));
        assertTrue(names.contains("throwing_avail_probe"), names.toString());
    }

}
