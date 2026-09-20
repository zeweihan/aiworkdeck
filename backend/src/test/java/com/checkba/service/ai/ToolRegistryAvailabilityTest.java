// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    // ==================== 运行期可用性（dev-board#750） ====================

    /**
     * 一半工具依赖外部服务、一半是纯本地的组件——现实里 WebTools / LegalTools 就是这个形状
     * （search_web 要账户、browse_url 不要；law_* 要账户、read_document 不要）。
     */
    static class HalfRemoteTools implements AgentToolComponent {
        boolean connected = true;

        @Override
        public java.util.Set<String> currentlyUnusableTools() {
            return connected ? java.util.Set.of() : java.util.Set.of("remote_probe");
        }

        @Tool("needs the account")
        public String remote_probe(@P("t") String t) {
            return "remote:" + t;
        }

        @Tool("purely local, never gated")
        public String local_probe(@P("t") String t) {
            return "local:" + t;
        }
    }

    /** currentlyUnusableTools() 抛异常时必须当成「没有不可用的工具」。 */
    static class ThrowingRuntimeGate implements AgentToolComponent {
        @Override
        public java.util.Set<String> currentlyUnusableTools() {
            throw new IllegalStateException("gate blew up");
        }

        @Tool("probe whose runtime gate throws")
        public String runtime_throwing_probe(@P("t") String t) {
            return "t:" + t;
        }
    }

    @Test
    @DisplayName("未连接时那几个工具不下发，同组件里的本地工具照常下发")
    void runtimeUnusableToolsAreHiddenWhileLocalOnesStay() {
        HalfRemoteTools tools = new HalfRemoteTools();
        ToolRegistry registry = registryOf(tools);

        tools.connected = false;
        java.util.Set<String> unusable = registry.unusableToolNames();
        assertEquals(java.util.Set.of("remote_probe"), unusable, unusable.toString());

        // 编排器拿到注册表的清单后再去掉 unusable（见 AgentOrchestrator：可见性的最后一道
        // 裁剪本来就在那里）。这里复刻那一步。
        List<String> names = registry.getAllSpecifications("conv", null).stream()
                .map(ToolSpecification::name).filter(n -> !unusable.contains(n)).toList();
        assertFalse(names.contains("remote_probe"), "没连账户时它每次都只会回一句「不可用」：" + names);
        assertTrue(names.contains("local_probe"), "同组件里的本地工具绝不能跟着一起藏：" + names);
    }

    @Test
    @DisplayName("连上之后立刻回到全集——这是运行期判定，不是启动时探一次")
    void connectingBringsTheToolBack() {
        HalfRemoteTools tools = new HalfRemoteTools();
        ToolRegistry registry = registryOf(tools);

        tools.connected = true;
        assertTrue(registry.unusableToolNames().isEmpty());
        List<String> names = specNames(registry);
        assertTrue(names.contains("remote_probe"), names.toString());
        assertTrue(names.contains("local_probe"), names.toString());
    }

    @Test
    @DisplayName("运行期不可用也只裁 spec，不裁 resolve/execute")
    void runtimeHiddenToolIsStillDispatchable() {
        HalfRemoteTools tools = new HalfRemoteTools();
        ToolRegistry registry = registryOf(tools);
        tools.connected = false;

        assertTrue(registry.resolve("remote_probe").isPresent(),
                "XML 兜底路径调到时要拿到工具自己那句可行动的错误，而不是 'tool not found'");
    }

    @Test
    @DisplayName("运行期判定抛异常 = 没有不可用的工具（判不准一律倒向全集）")
    void throwingRuntimeGateHidesNothing() {
        ToolRegistry registry = registryOf(new ThrowingRuntimeGate());
        assertTrue(registry.unusableToolNames().isEmpty());
        List<String> names = specNames(registry);
        assertTrue(names.contains("runtime_throwing_probe"), names.toString());
    }

}
