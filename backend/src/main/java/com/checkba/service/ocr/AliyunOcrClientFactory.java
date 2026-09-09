// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ocr;

import org.springframework.util.StringUtils;
import com.aliyun.teaopenapi.models.Config;

/**
 * 阿里云 OCR Client 工厂
 */
public class AliyunOcrClientFactory {

    public static AliyunOcrClient create(String accessKeyId, String accessKeySecret, String endpoint, String regionId) {
        // 新版 OCR SDK（ocr-api 2021-07-07 / Java Tea）
        // endpoint 默认：ocr-api.cn-hangzhou.aliyuncs.com
        String ep = StringUtils.hasText(endpoint) ? endpoint.trim() : "ocr-api.cn-hangzhou.aliyuncs.com";
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setEndpoint(ep);
        return new AliyunOcrClient(config);
    }
}


