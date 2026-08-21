package com.checkba.plugin.api;

/** 标签：同名不同型复用不改型。 */
public interface Tags {
    TagInfo getOrCreate(long projectId, String name, String type);
    void tagFile(long projectId, long fileId, long tagId);
    java.util.List<TagInfo> tagsOf(long projectId, long fileId);
}
