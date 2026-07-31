package com.checkba.version;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * .awd/tree.json 的 Java 表示。
 * v1：节点带本机数据库 id/parentId/filePath/userId——只在单机内有意义。
 * v2：身份改稳定 uid（UUID），路径存 repo 相对 relPath，署名存 author 用户名——
 * 跨机器可用（云端协作前提）。v2 清单里 v1 那四个本机字段一律 null 不落盘。
 * 读取端两版都认：apply 入口按 version 分派（v2 先归一化，v1 原路径）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreeManifest(int version, List<Node> nodes) {

    public static final int CURRENT_VERSION = 2;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(Long id, Long parentId, String name, boolean isFolder,
                       String fileType, Integer sortOrder, String filePath,
                       boolean isDeleted, Long userId,
                       String uid, String parentUid, String relPath, String author) {}
}
