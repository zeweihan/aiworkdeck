package com.checkba.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 本地文件系统存储服务实现
 */
@Service
public class LocalFileStorageService implements StorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalFileStorageService.class);

    private final ProjectStorageResolver storageResolver;
    private final Path templateDoc;

    @Autowired
    public LocalFileStorageService(ProjectStorageResolver storageResolver) {
        this.storageResolver = storageResolver;
        this.templateDoc = storageResolver.templateDoc();
        log.info("本地存储服务初始化完成，全局存储根: {}", storageResolver.globalRoot());
    }

    @Override
    public String save(String fileId, InputStream inputStream) throws StorageException {
        Path filePath = resolveFilePath(fileId);
        
        try {
            // 确保父目录存在
            Files.createDirectories(filePath.getParent());
            
            // 保存文件
            Files.copy(inputStream, filePath, 
                    StandardCopyOption.REPLACE_EXISTING);
            
            log.info("文件保存成功: fileId={}, path={}", fileId, filePath);

            // 返回存储 key 本身（调用方把返回值继续当 key 用：getSize/RAG/打标签。
            // 旧实现 relativize(storageDir) 对托管项目等价于 key；localRoot 项目
            // 会退化成绝对路径、再当 key 解析必然越界，故改为原样返回）
            return fileId;
        } catch (IOException e) {
            log.error("文件保存失败: fileId={}, path={}", fileId, filePath, e);
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 纯读。文件不存在就抛，<b>绝不就地造一个出来</b>。
     *
     * <p>此前这里会「文件不存在就从模板复制一份」并当成正常结果返回：一份正文丢失的合同
     * （换存储位置、同步失败、只恢复了数据库、localRoot 项目里被外部删掉）被读成一份空白模板，
     * 用户打开看到空文档、AI 读到模板内容，全程零报错；自动保存再把空白盖回去，原件就真没了。
     * 而且这与接口契约、与 {@link OssStorageService#load} 的行为都不一致（那边一直是抛）。
     * 新建文档的模板物化改由 {@link #createFromTemplate(String)} 明确表达。
     */
    @Override
    public Resource load(String fileId) throws StorageException {
        Path filePath = resolveFilePath(fileId);

        FileSystemResource resource = new FileSystemResource(filePath);
        if (!resource.exists()) {
            log.error("文件不存在: fileId={}, path={}", fileId, filePath);
            throw new StorageException("文件不存在: " + fileId);
        }

        return resource;
    }

    /** 新建文档时物化模板文件；已存在则原样不动（幂等）。 */
    @Override
    public void createFromTemplate(String fileId) throws StorageException {
        Path filePath = resolveFilePath(fileId);
        if (Files.exists(filePath)) {
            return;
        }
        try {
            Files.createDirectories(filePath.getParent());
            if (Files.exists(templateDoc)) {
                Files.copy(templateDoc, filePath, StandardCopyOption.REPLACE_EXISTING);
                log.info("从模板创建新文件: fileId={}, path={}", fileId, filePath);
            } else {
                log.warn("模板文件不存在: {}, 创建空文件", templateDoc);
                Files.createFile(filePath);
            }
        } catch (IOException e) {
            log.error("创建文件失败: fileId={}, path={}", fileId, filePath, e);
            throw new StorageException("创建文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String fileId) throws StorageException {
        Path filePath = resolveFilePath(fileId);
        
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("文件删除成功: fileId={}, path={}", fileId, filePath);
            } else {
                log.warn("文件不存在，跳过删除: fileId={}, path={}", fileId, filePath);
            }
        } catch (IOException e) {
            log.error("文件删除失败: fileId={}, path={}", fileId, filePath, e);
            throw new StorageException("文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        Path filePath = resolveFilePath(fileId);
        return Files.exists(filePath);
    }

    @Override
    public String getUrl(String fileId) {
        return null;
    }

    /**
     * 解析文件路径
     * 如果 fileId 包含路径分隔符，则直接使用；否则兼容旧逻辑添加 .docx。
     * 逻辑路径 → 物理路径（含越界围栏）统一委托 ProjectStorageResolver。
     */
    private Path resolveFilePath(String fileId) {
        String relative = (fileId.contains("/") || fileId.contains("\\")) ? fileId : fileId + ".docx";
        return storageResolver.resolve(relative);
    }

    @Override
    public String append(String fileId, InputStream inputStream) throws StorageException {
        Path filePath = resolveFilePath(fileId);

        try {
            // 确保父目录存在
            if (!Files.exists(filePath.getParent())) {
                Files.createDirectories(filePath.getParent());
            }

            // 如果文件不存在，先创建
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
            }

            // 追加内容
            try (var outputStream = Files.newOutputStream(filePath, StandardOpenOption.APPEND)) {
                inputStream.transferTo(outputStream);
            }

            log.debug("文件追加成功: fileId={}, path={}", fileId, filePath);

            // 同 save()：原样返回存储 key
            return fileId;
        } catch (IOException e) {
            log.error("文件追加失败: fileId={}, path={}", fileId, filePath, e);
            throw new StorageException("文件追加失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long getSize(String fileId) {
        Path filePath = resolveFilePath(fileId);
        try {
            if (Files.exists(filePath)) {
                return Files.size(filePath);
            }
        } catch (IOException e) {
            log.warn("获取文件大小失败: {}", fileId, e);
        }
        return 0;
    }
}
