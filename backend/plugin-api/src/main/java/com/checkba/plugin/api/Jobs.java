package com.checkba.plugin.api;

/** 后台任务：每插件最多 2 并发，多余的排队；进度经 REST 与 SSE 推给前端。 */
public interface Jobs {
    JobHandle start(String kind, String title, JobBody body);
    JobStatus status(String jobId);
    void cancel(String jobId);
}
