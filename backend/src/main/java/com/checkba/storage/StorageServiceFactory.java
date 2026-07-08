package com.checkba.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 存储服务工厂
 * 根据配置自动选择使用本地存储或对象存储
 */
@Component
@Primary
public class StorageServiceFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageServiceFactory.class);

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private LocalFileStorageService localFileStorageService;

    @Autowired
    private OssStorageService ossStorageService;

    /**
     * 获取存储服务实例
     */
    public StorageService getStorageService() {
        String type = storageProperties.getType();

        log.info("获取存储服务，类型: {}", type);

        // 空值防御：配置里 storage.type: 键存在但值为空时 type 为 null，避免 toLowerCase NPE 拖垮全站文件功能
        if (type == null) return localFileStorageService;
        switch (type.toLowerCase()) {
            case "local":
                return localFileStorageService;
            case "oss":
            case "s3":
            case "object":
                return ossStorageService;
            default:
                log.warn("未知的存储类型: {}, 使用默认本地存储", type);
                return localFileStorageService;
        }
    }
}

