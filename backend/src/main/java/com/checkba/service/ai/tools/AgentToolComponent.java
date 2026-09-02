package com.checkba.service.ai.tools;

/**
 * 内置 Agent 工具组件的标记接口。
 * 实现此接口的 Spring Bean 会被 ToolRegistry 自动扫描，
 * 其中的 @Tool 方法注册为可分发工具——新增工具类只需实现本接口，无需改动编排器。
 */
public interface AgentToolComponent {

    /**
     * 本机上这个组件的工具是否值得下发给模型。默认 true。
     *
     * <p>返回 false 时 ToolRegistry 仍然登记这些工具（万一被 XML 兜底路径调到，
     * 走的还是工具自己那句可行动的错误），但<b>不把它们的 spec 下发给模型</b>——
     * 模型看不见即不会去试。存在的理由是 run_python：它无条件依赖本机 Docker，
     * 而绝大多数用户机器上没有 Docker，模型把它当成"读图/OCR 的备选路子"调用，
     * 撞上 "Cannot run program docker" 后自己得出「OCR 环境不可用」的错误结论。
     *
     * <p>实现必须自己缓存探测结果并且<b>绝不抛异常</b>：本方法在 ToolRegistry
     * 的 @PostConstruct 里调用，抛出去就是后端起不来。
     */
    default boolean isAvailable() {
        return true;
    }
}
