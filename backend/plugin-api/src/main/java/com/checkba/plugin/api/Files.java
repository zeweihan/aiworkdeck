package com.checkba.plugin.api;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** 项目文件树（读写都先校项目成员权限）。 */
public interface Files {
    /** parentId 为 null 表示项目根；recursive=true 时返回整棵子树（路径已拼好）。 */
    List<FileInfo> list(long projectId, Long parentId, boolean recursive);
    FileInfo get(long projectId, long fileId);
    InputStream open(long projectId, long fileId);
    /** 逐级确保文件夹存在，返回最深一级。 */
    FileInfo createFolderPath(long projectId, List<String> segments);
    FileInfo write(long projectId, Long parentId, String name, InputStream bytes, ConflictPolicy policy);
    FileInfo move(long projectId, long fileId, Long newParentId);
    FileInfo rename(long projectId, long fileId, String newName);
    /** 浅合并进 metaJson（值为 null 的键删除）。 */
    void setMeta(long projectId, long fileId, Map<String, Object> metaPatch);
    /** 宿主算一次并缓存到 metaJson.sha256（连同 sha256At）。 */
    String sha256(long projectId, long fileId);
}
