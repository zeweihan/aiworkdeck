package com.checkba.plugin.api;

/** 后台任务体内可用的上下文：汇报进度、响应取消、读取发起时的调用快照、写入结果。 */
public interface JobContext {
    void progress(long done, long total, String message);
    /** 已被取消时抛 InterruptedException；任务体应在循环里定期调用。 */
    void checkCancelled() throws InterruptedException;
    /** 任务发起时的调用上下文快照（projectId/userId/conversationId），后台线程上 {@link PluginHost#call()} 为 null 时用它。 */
    ToolCall call();
    void result(String resultJson);
}
