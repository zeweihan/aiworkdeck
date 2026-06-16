package com.checkba.service;

import com.checkba.exception.FeatureNotConfiguredException;
import com.checkba.service.ocr.AliyunOcrClientFactory;
import com.checkba.service.ocr.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * OCR 服务（目前实现：阿里云 OCR）
 */
@Service
@RequiredArgsConstructor
public class OcrService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OcrService.class);

    private final SystemSettingService systemSettingService;

    /**
     * 通用 OCR 识别（图片 base64，支持 dataURL）
     */
    public OcrResult recognizeGeneral(String imageBase64) {
        if (!StringUtils.hasText(imageBase64)) {
            throw new IllegalArgumentException("imageBase64 不能为空");
        }

        // 支持 data:image/png;base64,xxxx
        String payload = imageBase64.trim();
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }

        // 轻量校验：确保是合法 base64（不解码也能调用，但这里能提前发现明显错误）
        try {
            Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imageBase64 格式不正确");
        }

        String ak = systemSettingService.get("external.aliyunOcr.accessKeyId", "");
        String sk = systemSettingService.get("external.aliyunOcr.accessKeySecret", "");
        // 新版 ocr-api 2021-07-07 endpoint 默认：ocr-api.cn-hangzhou.aliyuncs.com
        String endpoint = systemSettingService.get("external.aliyunOcr.endpoint", "ocr-api.cn-hangzhou.aliyuncs.com");
        String regionId = systemSettingService.get("external.aliyunOcr.regionId", "");

        ak = ak == null ? "" : ak.trim();
        sk = sk == null ? "" : sk.trim();
        endpoint = endpoint == null ? "" : endpoint.trim();
        regionId = regionId == null ? "" : regionId.trim();

        if (!StringUtils.hasText(ak) || !StringUtils.hasText(sk)) {
            throw new FeatureNotConfiguredException("ocr",
                    "OCR 未配置：请在设置中配置阿里云 OCR AccessKey / "
                            + "OCR is not configured: set up Aliyun OCR keys in Settings.");
        }

        // 脱敏诊断：只打印长度与尾号，定位“secret 为空/带空格/不成对”
        try {
            String akTail = ak.length() <= 4 ? ak : ak.substring(ak.length() - 4);
            log.info("OCR config: endpoint={}, regionId={}, akTail=****{}, skLen={}",
                    StringUtils.hasText(endpoint) ? endpoint : "(empty)",
                    StringUtils.hasText(regionId) ? regionId : "(empty)",
                    akTail,
                    sk.length());
        } catch (Exception ignore) {
            // ignore
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            var client = AliyunOcrClientFactory.create(ak, sk, endpoint, regionId);
            return client.recognizeGeneral(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.error("OCR 识别失败", e);
            throw new RuntimeException("OCR 识别失败: " + e.getMessage(), e);
        }
    }
}


