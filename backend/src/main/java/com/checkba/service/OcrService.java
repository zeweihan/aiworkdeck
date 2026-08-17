package com.checkba.service;

import com.checkba.exception.FeatureNotConfiguredException;
import com.checkba.service.ocr.AliyunOcrClientFactory;
import com.checkba.service.ocr.OcrResult;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;

/**
 * OCR 服务：平台代采（网关）与自备阿里云 Key 两档。
 *
 * <p>分档在这一层，两档各自完整：platform 档完全不碰 {@code AliyunOcrClientFactory}，
 * byok 档一字未动。<b>平台档失败绝不静默回落 byok</b>——回落会去花用户自己的
 * 阿里云账号（licensing-billing 地雷 8 / 27）。
 */
@Service
@RequiredArgsConstructor
public class OcrService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OcrService.class);

    /** OCR 高精版对整页扫描件经常要十几秒，账户通道那 5 秒在这里必然误判成故障。 */
    private static final int GATEWAY_TIMEOUT_SECONDS = 60;

    private final SystemSettingService systemSettingService;
    private final ExternalProviderResolver externalProviderResolver;
    private final PlatformGatewayClient platformGatewayClient;

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

        if (externalProviderResolver.resolve(ExternalServiceProvider.OCR)
                == ExternalServiceProvider.PLATFORM) {
            return recognizeViaPlatform(payload);
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

    /**
     * 平台代采档：官网持凭证调阿里云 OCR，按实际页数扣 Credits。
     *
     * <p>{@link com.checkba.service.platform.GatewayException} 原样抛出去，由
     * {@code GlobalExceptionHandler} 按 kind 落成 code=1 的业务错误——包成
     * RuntimeException 会让「未开放 / 上游挂 / 我们挂 / 余额不足」全变成
     * 一句「服务器内部错误」，用户不知道下一步该做什么。
     */
    private OcrResult recognizeViaPlatform(String payload) {
        PlatformGatewayClient.Result result = platformGatewayClient.call(
                "ocr", "recognize", Map.of("imageBase64", payload), GATEWAY_TIMEOUT_SECONDS);
        JsonNode data = result.data();
        // 与 byok 档同一形状（text + raw）：消费方（截图摘录、文件文本抽取）
        // 不该按档位分两套解析
        return new OcrResult(data.path("content").asText(""), data.path("raw").asText(""));
    }
}


