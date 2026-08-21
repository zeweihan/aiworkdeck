package com.checkba.plugin.api;

/**
 * 项目文件树节点。{@code path} 是项目根起的逻辑路径（"a/b/c.docx"）；
 * {@code sha256} 只在宿主已计算并缓存时非空（见 {@link Files#sha256}）；{@code metaJson} 为原始 JSON 串，可为 null；
 * {@code updatedAt} 为最后修改时间（epoch millis，可为 null；1.0.0 增量字段，永远是最后一个分量）。
 */
public record FileInfo(long id, String name, Long parentId, boolean folder, String fileType, long size,
                       String path, String sha256, String metaJson, Long updatedAt) {}
