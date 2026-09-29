// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 存储服务工厂。
 * 文件真相源是本机磁盘（ProjectStorageResolver 映射逻辑路径），只有本地存储一种实现。
 */
@Component
@Primary
public class StorageServiceFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageServiceFactory.class);

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private LocalFileStorageService localFileStorageService;

    /**
     * 获取存储服务实例
     */
    public StorageService getStorageService() {
        String type = storageProperties.getType();
        if (type != null && !type.isBlank() && !"local".equalsIgnoreCase(type)) {
            log.warn("不支持的存储类型: {}, 使用本地存储", type);
        }
        return localFileStorageService;
    }
}

