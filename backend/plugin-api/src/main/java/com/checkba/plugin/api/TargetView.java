package com.checkba.plugin.api;

/** 证据链接的一个底稿目标。 */
public record TargetView(long id, long fileId, String fileName, String locatorJson, String relation, String method) {}
