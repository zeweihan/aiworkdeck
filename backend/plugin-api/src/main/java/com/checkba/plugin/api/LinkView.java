package com.checkba.plugin.api;

/** 证据链接视图（精简版）。 */
public record LinkView(long id, String linkKey, long docFileId, String anchorText, String sectionPath,
                       String sectionTitle, String status, java.util.List<TargetView> targets) {}
