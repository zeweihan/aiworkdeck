package com.checkba.plugin.api;

/** 证据链接（createdByKind 恒为 plugin）。 */
public interface Evidence {
    LinkView create(long projectId, long docFileId, String linkKey, String anchorText, String sectionPath,
                    String sectionTitle, java.util.List<TargetInput> targets);
    LinkView addTargets(long projectId, String linkKey, java.util.List<TargetInput> targets);
    java.util.List<LinkView> listByDoc(long projectId, long docFileId);
    java.util.List<LinkView> listByFile(long projectId, long fileId);
}
