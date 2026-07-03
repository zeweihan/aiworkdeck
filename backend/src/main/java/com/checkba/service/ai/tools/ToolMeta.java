package com.checkba.service.ai.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的产品层元数据，与 langchain4j 的 @Tool（面向 LLM 的描述）互补。
 * 声明式地替代编排器里手写的展示名映射和文件副作用通知逻辑，
 * 新增工具无需再修改编排器。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMeta {

    /**
     * 面向用户的中文显示名（历史记录、过程气泡中展示）。
     */
    String displayName() default "";

    /**
     * 工具分类（file/legal/web/memory/document/pptx/python/plugin），用于后续的可见性控制与插件广场分组。
     */
    String category() default "";

    /**
     * 执行成功后对项目文件的影响："ADDED" 或 "MODIFIED"，空串表示无文件副作用。
     */
    String fileEffect() default "";

    /**
     * 携带受影响文件名的参数名；fileEffect 非空但此项为空时，前端显示为"Current Document"。
     */
    String fileArg() default "";

    /**
     * 执行成功后是否通知前端刷新文件树。
     */
    boolean refreshFiles() default false;
}
