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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 本地文件系统存储服务实现
 */
@Service
public class LocalFileStorageService implements StorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalFileStorageService.class);

    private final StorageProperties storageProperties;
    private final Path storageDir;
    private final Path templateDoc;

    @Autowired
    public LocalFileStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        
        // 解析存储目录路径
        String rootPath = storageProperties.getLocal().getRootPath();
        Path root;
        // 如果是绝对路径直接使用，否则基于 user.dir 解析
        // 注意：这里需要更健壮的 user.dir 处理，以防在不同目录启动
        // 建议从 storageProperties 拿到绝对路径
        if (Paths.get(rootPath).isAbsolute()) {
            root = Paths.get(rootPath);
        } else {
            // 假设开发环境，user.dir 是 backend 目录
            String userDir = System.getProperty("user.dir");
            Path projectRoot = Paths.get(userDir);
            if (userDir.endsWith("backend")) {
                projectRoot = projectRoot.getParent();
            }
            root = projectRoot.resolve(rootPath);
        }
        this.storageDir = root;
        
        // 解析模板文件路径
        String templatePath = storageProperties.getLocal().getTemplatePath();
        if (Paths.get(templatePath).isAbsolute()) {
            this.templateDoc = Paths.get(templatePath);
        } else {
            String userDir = System.getProperty("user.dir");
            Path projectRoot = Paths.get(userDir);
            if (userDir.endsWith("backend")) {
                projectRoot = projectRoot.getParent();
            }
            this.templateDoc = projectRoot.resolve(templatePath);
        }
        
        log.info("本地存储服务初始化完成，存储目录: {}", storageDir);
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
            
            // 返回相对路径（相对于 storageDir）
            try {
                return storageDir.relativize(filePath).toString();
            } catch (IllegalArgumentException e) {
                // 如果不在 storageDir 下，返回绝对路径
                return filePath.toString();
            }
        } catch (IOException e) {
            log.error("文件保存失败: fileId={}, path={}", fileId, filePath, e);
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Resource load(String fileId) throws StorageException {
        Path filePath = resolveFilePath(fileId);
        
        // 如果文件不存在，尝试从模板创建
        if (!Files.exists(filePath)) {
            try {
                // 确保父目录存在
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
        
        FileSystemResource resource = new FileSystemResource(filePath);
        if (!resource.exists()) {
            log.error("文件不存在: fileId={}, path={}", fileId, filePath);
            throw new StorageException("文件不存在: " + fileId);
        }
        
        return resource;
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
     * 如果 fileId 包含路径分隔符，则直接使用；否则兼容旧逻辑添加 .docx
     *
     * 安全围栏：对 fileId 做 normalize 后校验解析结果仍落在 storageDir 内，
     * 防止 fileId 含 "../" 逃出存储根读写/删除任意文件（纵深防御的最后一道）。
     */
    private Path resolveFilePath(String fileId) {
        String relative = (fileId.contains("/") || fileId.contains("\\")) ? fileId : fileId + ".docx";
        Path base = storageDir.normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            log.warn("拒绝越界文件路径: fileId={}, resolved={}", fileId, resolved);
            throw new StorageException("非法文件路径（越出存储根）: " + fileId);
        }
        return resolved;
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

            try {
                return storageDir.relativize(filePath).toString();
            } catch (IllegalArgumentException e) {
                return filePath.toString();
            }
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
