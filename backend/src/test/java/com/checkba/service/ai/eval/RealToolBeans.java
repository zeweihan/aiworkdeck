// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.AskUserTools;
import com.checkba.service.ai.tools.DdExportTools;
import com.checkba.service.ai.tools.DocumentAuditTools;
import com.checkba.service.ai.tools.DocumentEditTools;
import com.checkba.service.ai.tools.EvidenceTools;
import com.checkba.service.ai.tools.FileTools;
import com.checkba.service.ai.tools.EnterpriseDataTools;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ai.tools.LitigationTimelineTools;
import com.checkba.service.ai.tools.LitigationVisualTools;
import com.checkba.service.ai.tools.MeetingTools;
import com.checkba.service.ai.tools.MemoryTools;
import com.checkba.service.ai.tools.OfficeEditTools;
import com.checkba.service.ai.tools.ContributedTemplateTools;
import com.checkba.service.ai.tools.PdfTools;
import com.checkba.service.ai.tools.CapabilityTools;
import com.checkba.service.ai.tools.CheckpointTools;
import com.checkba.service.ai.tools.PluginDevTools;
import com.checkba.service.ai.tools.PptxTools;
import com.checkba.service.ai.tools.PythonTools;
import com.checkba.service.ai.tools.ReferenceTools;
import com.checkba.service.ai.tools.SlideEditTools;
import com.checkba.service.ai.tools.SubAgentTools;
import com.checkba.service.ai.tools.TagTools;
import com.checkba.service.ai.tools.TaskTools;
import com.checkba.service.ai.tools.TemplateTools;
import com.checkba.service.ai.tools.TextFileEditTools;
import com.checkba.service.ai.tools.TodoTools;
import com.checkba.service.ai.tools.ToolDiscoveryTools;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.ai.tools.WebVerifyTools;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 以「空依赖」反射实例化全部生产工具组件。
 *
 * 目的：让评测用的 ToolRegistry 注册的是真实的工具名与参数名——
 * 若重构改了工具名/参数名/别名，评测会立刻失败（这正是回归信号）。
 *
 * 安全性：评测中 {@link RecordingToolRegistry} 只记录分发、从不 invoke 工具方法，
 * 因此构造参数全部传 null 不会触发任何 NPE。
 */
final class RealToolBeans {

    private RealToolBeans() {
    }

    /**
     * 渐进披露开关，只影响 {@link ToolDiscoveryTools#isAvailable()}（也就是 list_tools 下不下发规格）。
     * 默认 true：绝大多数测试要么不在乎它，要么正是要断言目录工具在场。
     * 想量「生产默认（披露关着）到底下发多少工具」时用 {@link #instantiateAll(boolean)}。
     */
    static List<AgentToolComponent> instantiateAll() {
        return instantiateAll(true);
    }

    /** 与生产 Spring 容器中注册的 AgentToolComponent 集合保持一致 */
    static List<AgentToolComponent> instantiateAll(boolean disclosureEnabled) {
        DISCLOSURE_ENABLED.set(disclosureEnabled);
        try {
            return instantiateComponents();
        } finally {
            DISCLOSURE_ENABLED.set(true);
        }
    }

    private static final ThreadLocal<Boolean> DISCLOSURE_ENABLED = ThreadLocal.withInitial(() -> true);

    private static List<AgentToolComponent> instantiateComponents() {
        List<Class<? extends AgentToolComponent>> toolClasses = List.of(
                // ask_user（dev-board#868）：会结束本轮的提问工具，编排器按它停机
                AskUserTools.class,
                CapabilityTools.class,
                // 与 TodoTools 同一个坑：CheckpointTools（doc_restore_checkpoint）与
                // SlideEditTools（slide_* 全族）长期漏列，于是所有针对它们的
                // offeredToolsExclude 断言都是空断言（工具名压根没注册，排除断言恒过）。
                // dev-board#729 ① 要断言「docx 活跃时 slide_* 被裁掉」，先把它们补进来，
                // 否则那条用例即使裁剪整个失效也照样绿。
                CheckpointTools.class,
                ContributedTemplateTools.class,
                DdExportTools.class,
                DocumentAuditTools.class,
                DocumentEditTools.class,
                EnterpriseDataTools.class,
                EvidenceTools.class,
                FileTools.class,
                LegalTools.class,
                LitigationTimelineTools.class,
                LitigationVisualTools.class,
                MeetingTools.class,
                MemoryTools.class,
                OfficeEditTools.class,
                PdfTools.class,
                PluginDevTools.class,
                PptxTools.class,
                PythonTools.class,
                // ref_* 只对 OFFICE 会话可见（dev-board#717），回放用例默认 LOWA，不改变既有可见工具集
                ReferenceTools.class,
                SlideEditTools.class,
                SubAgentTools.class,
                TextFileEditTools.class,
                // TodoTools 长期漏列：todo_write 在整个回放评测里根本没注册，
                // 于是「skill 命中时清单工具是否可见」这类断言写了也是空的（工具名不存在，
                // offeredToolsInclude 永远失败、offeredToolsExclude 永远通过）。
                // 补进来后 skill-orchestration-tools-not-trimmed 才真正有意义。
                TodoTools.class,
                // list_tools：工具目录（dev-board#810）。渐进披露默认关着，它也照常下发——
                // 多一个便宜的目录入口不会改变任何既有用例的工具选择，而漏列它会让
                // ToolDisclosurePolicyTest 的覆盖面断言全部变成空断言。
                ToolDiscoveryTools.class,
                TagTools.class,
                TaskTools.class,
                TemplateTools.class,
                WebTools.class,
                WebVerifyTools.class);
        List<AgentToolComponent> beans = new ArrayList<>();
        for (Class<? extends AgentToolComponent> type : toolClasses) {
            beans.add(instantiate(type));
        }
        return beans;
    }

    private static AgentToolComponent instantiate(Class<? extends AgentToolComponent> type) {
        Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalStateException(type.getSimpleName() + " 没有可用构造函数"));
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = defaultValue(paramTypes[i]);
        }
        try {
            ctor.setAccessible(true);
            return (AgentToolComponent) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法实例化工具组件 " + type.getSimpleName(), e);
        }
    }

    private static Object defaultValue(Class<?> t) {
        // 渐进披露策略是无状态的纯逻辑，给个真的：ToolDiscoveryTools 拿到 null 就只会
        // 回一句错误，list_tools 在回放里等于没接上（dev-board#810）。
        if (t == com.checkba.service.ai.ToolDisclosurePolicy.class) {
            return new com.checkba.service.ai.ToolDisclosurePolicy(DISCLOSURE_ENABLED.get());
        }
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) return Boolean.FALSE;
        if (t == char.class) return '\0';
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        return 0d;
    }
}
