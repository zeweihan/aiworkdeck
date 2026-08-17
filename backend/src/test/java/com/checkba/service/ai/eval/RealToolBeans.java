package com.checkba.service.ai.eval;

import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.DocumentEditTools;
import com.checkba.service.ai.tools.EvidenceTools;
import com.checkba.service.ai.tools.FileTools;
import com.checkba.service.ai.tools.EnterpriseDataTools;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ai.tools.LitigationVisualTools;
import com.checkba.service.ai.tools.MeetingTools;
import com.checkba.service.ai.tools.MemoryTools;
import com.checkba.service.ai.tools.OfficeEditTools;
import com.checkba.service.ai.tools.PdfTools;
import com.checkba.service.ai.tools.PptxTools;
import com.checkba.service.ai.tools.PythonTools;
import com.checkba.service.ai.tools.SubAgentTools;
import com.checkba.service.ai.tools.TodoTools;
import com.checkba.service.ai.tools.WebTools;

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

    /** 与生产 Spring 容器中注册的 AgentToolComponent 集合保持一致 */
    static List<AgentToolComponent> instantiateAll() {
        List<Class<? extends AgentToolComponent>> toolClasses = List.of(
                DocumentEditTools.class,
                EnterpriseDataTools.class,
                EvidenceTools.class,
                FileTools.class,
                LegalTools.class,
                LitigationVisualTools.class,
                MeetingTools.class,
                MemoryTools.class,
                OfficeEditTools.class,
                PdfTools.class,
                PptxTools.class,
                PythonTools.class,
                SubAgentTools.class,
                // TodoTools 长期漏列：todo_write 在整个回放评测里根本没注册，
                // 于是「skill 命中时清单工具是否可见」这类断言写了也是空的（工具名不存在，
                // offeredToolsInclude 永远失败、offeredToolsExclude 永远通过）。
                // 补进来后 skill-orchestration-tools-not-trimmed 才真正有意义。
                TodoTools.class,
                WebTools.class);
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
