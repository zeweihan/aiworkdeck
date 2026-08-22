package com.checkba.plugin.api;

/** 证据链接（createdByKind 恒为 plugin）。 */
public interface Evidence {
    LinkView create(long projectId, long docFileId, String linkKey, String anchorText, String sectionPath,
                    String sectionTitle, java.util.List<TargetInput> targets);
    LinkView addTargets(long projectId, String linkKey, java.util.List<TargetInput> targets);

    /**
     * 按报告原文片段建链：宿主负责查引文（必须恰好命中一次）、打书签、套内部超链接、取章节路径并落库，
     * 与 AI 工具 doc_link_evidence 同一份实现。插件不要自己拼这套 worker 原语。
     *
     * @throws IllegalArgumentException 引文命中 0 处或多处（消息可直接转述给用户）
     * @throws IllegalStateException    当前没有打开的会话/文档
     */
    LinkView linkAtQuote(long projectId, long docFileId, String anchorQuote, java.util.List<TargetInput> targets);
    java.util.List<LinkView> listByDoc(long projectId, long docFileId);
    java.util.List<LinkView> listByFile(long projectId, long fileId);
}
