package com.checkba.service.ai.tools;

/**
 * 内置 Agent 工具组件的标记接口。
 * 实现此接口的 Spring Bean 会被 ToolRegistry 自动扫描，
 * 其中的 @Tool 方法注册为可分发工具——新增工具类只需实现本接口，无需改动编排器。
 */
public interface AgentToolComponent {
}
