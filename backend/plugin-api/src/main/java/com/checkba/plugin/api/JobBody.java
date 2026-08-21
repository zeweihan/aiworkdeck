package com.checkba.plugin.api;

@FunctionalInterface
public interface JobBody {
    void run(JobContext ctx) throws Exception;
}
