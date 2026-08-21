package com.checkba.plugin.api;

/**
 * 宿主门面：插件拿到项目文件/文本抽取/标签/证据链接/后台任务/编辑器/设置/LLM 的唯一入口。
 * 每个方法在宿主侧都先做项目成员鉴权，并受每插件每分钟的调用配额约束（超限抛 {@link HostQuotaException}）。
 */
public interface PluginHost {
    String pluginId();
    /** 当前工具调用上下文（ThreadLocal 透传）；非工具调用期（如后台任务线程）为 null，此时用 {@link JobContext#call()}。 */
    ToolCall call();
    Files files();
    Text text();
    Tags tags();
    Evidence evidence();
    Jobs jobs();
    Docs docs();
    Settings settings();
    Llm llm();
}
