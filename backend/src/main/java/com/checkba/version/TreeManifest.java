package com.checkba.version;

import java.util.List;

/**
 * 某一版的完整文件树快照。
 *
 * 存在的理由：数据库才是文件树的真源，磁盘只是投影（软删除不动磁盘文件，
 * 物理重命名失败时数据库仍会改名）。只跟踪磁盘文件不足以还原一个版本。
 */
public record TreeManifest(int version, List<Node> nodes) {

    public static final int CURRENT_VERSION = 1;

    public record Node(
            Long id,
            Long parentId,
            String name,
            boolean isFolder,
            String fileType,
            Integer sortOrder,
            String filePath,
            boolean isDeleted
    ) {}
}
