package com.checkba.plugin.api;

/** 建链/追加时的一个底稿位置（与宿主 EvidenceLinkViews.TargetInput 字段一一对应）。 */
public record TargetInput(Long fileId, String locatorJson, String relation, String method, Short confidence, String note) {}
