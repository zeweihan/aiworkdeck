package com.checkba.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 文件存储服务接口
 * 
 * 统一抽象文件存储操作，支持本地文件系统和对象存储（OSS/S3等）
 * 实现类需要处理：
 * - 文件上传/保存
 * - 文件下载/读取
 * - 文件删除
 * - 文件是否存在检查
 */
public interface StorageService {

    /**
     * 保存文件
     * 
     * @param fileId 文件ID（用于生成存储路径）
     * @param inputStream 文件输入流
     * @return 存储路径（用于数据库记录）
     * @throws StorageException 存储异常
     */
    String save(String fileId, InputStream inputStream) throws StorageException;

    /**
     * 读取文件。
     *
     * <p><b>纯读操作：文件不存在必须抛 {@link StorageException}，绝不允许就地造一个出来。</b>
     * 本地实现曾在此处「文件不存在就从模板复制一份」，于是一份正文丢失的合同被读成一份
     * 空白模板，用户打开看到的是空文档、AI 读到的是模板内容，全程没有任何报错；
     * 自动保存再把这份空白盖回去，原件就真的没了。新建文档要物化模板请走
     * {@link #createFromTemplate(String)}——建和读是两件事，别再合用一个方法。
     *
     * @param fileId 文件ID
     * @return 文件资源
     * @throws StorageException 文件不存在或读取失败
     */
    Resource load(String fileId) throws StorageException;

    /**
     * 新建文档时物化一个初始文件（本地实现从 docs/template.docx 复制，缺模板则建空文件）。
     *
     * <p>只有「创建」路径可以调用；已存在则原样不动（幂等）。
     * 默认空实现：对象存储此前根本没有这条路（{@code load} 直接抛，调用方 catch 掉记一行日志），
     * 保持这个既有行为，不在本次修复里给 OSS 新增一次上传。
     */
    default void createFromTemplate(String fileId) throws StorageException {
        // no-op：见 javadoc
    }

    /**
     * 删除文件
     *
     * @param fileId 文件ID
     * @throws StorageException 存储异常
     */
    void delete(String fileId) throws StorageException;

    /**
     * 把 fromId 的内容挪到 toId（目标已存在则覆盖），成功后 fromId 不复存在。
     *
     * <p>「先写临时 key、再 move 顶替」是覆盖式落盘的原子化手段（插件文档镜像，
     * dev-board#299）：直接 save 到最终 key 中途失败会留半截文件。本地实现用
     * {@code Files.move}（同卷原子）；默认实现退化为 load→save→delete 流拷贝
     * （对象存储没有原子 move，覆盖窗口收窄到对象存储自身的 put 原子性）。
     */
    default void move(String fromId, String toId) throws StorageException {
        try (InputStream in = load(fromId).getInputStream()) {
            save(toId, in);
        } catch (java.io.IOException e) {
            throw new StorageException("文件移动失败: " + e.getMessage(), e);
        }
        delete(fromId);
    }

    /**
     * 检查文件是否存在
     * 
     * @param fileId 文件ID
     * @return 是否存在
     */
    boolean exists(String fileId);

    /**
     * 获取文件的访问URL（用于对象存储的预签名URL或直接访问URL）
     * 
     * @param fileId 文件ID
     * @return 访问URL，如果不需要URL则返回null
     */
    String getUrl(String fileId);

    /**
     * 追加内容到文件末尾 (用于断点续传)
     *
     * @param fileId      文件ID
     * @param inputStream 输入流
     * @return 存储路径
     * @throws StorageException 存储异常
     */
    String append(String fileId, InputStream inputStream) throws StorageException;

    /**
     * 获取文件当前大小
     *
     * @param fileId 文件ID
     * @return 文件大小 (字节)
     */
    long getSize(String fileId);
}

