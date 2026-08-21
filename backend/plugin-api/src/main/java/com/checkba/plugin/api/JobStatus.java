package com.checkba.plugin.api;

/** status 取值：queued / running / done / failed / cancelled。 */
public record JobStatus(String jobId, String kind, String title, String status, long done, long total,
                        String message, String resultJson, String error) {}
