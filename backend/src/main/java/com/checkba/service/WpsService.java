package com.checkba.service;

import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * WPS WebOffice 服务
 * 负责生成编辑链接、会话 token 等
 */
@Service
public class WpsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WpsService.class);

    private final SystemSettingService systemSettingService;

    @org.springframework.beans.factory.annotation.Autowired
    public WpsService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @Value("${external.wps.app-id:}")
    private String defaultAppId;

    @Value("${external.wps.app-secret:}")
    private String defaultAppSecret;

    @Value("${external.wps.callback-base-url:}")
    private String defaultCallbackBaseUrl;

    private static final String KEY_WPS_APP_ID = "external.wps.appId";
    private static final String KEY_WPS_APP_SECRET = "external.wps.appSecret";
    private static final String KEY_WPS_CALLBACK_BASE_URL = "external.wps.callbackBaseUrl";

    private String getAppId() {
        return systemSettingService.get(KEY_WPS_APP_ID, defaultAppId);
    }

    private String getAppSecret() {
        return systemSettingService.get(KEY_WPS_APP_SECRET, defaultAppSecret);
    }

    public String getCallbackBaseUrl() {
        return systemSettingService.get(KEY_WPS_CALLBACK_BASE_URL, defaultCallbackBaseUrl);
    }

    /**
     * 获取公开的 AppId（供前端 WPS SDK 初始化使用）
     * 注意：这是公开方法，仅返回 appId，不返回 appSecret
     */
    public String getPublicAppId() {
        return getAppId();
    }

    /**
     * WPS 文档编辑是否已配置（appId 与 appSecret 均非空）。
     * 未配置时前端应降级为只读预览而非加载编辑器（#18 T6）。
     */
    public boolean isConfigured() {
        String appId = getAppId();
        String appSecret = getAppSecret();
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * 生成 WPS 在线编辑链接（URL 直连方案预留）
     */
    public String generateEditUrl(String fileId, String fileName, String mode) {
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = generateSignature(fileId, timestamp, mode);
        String appId = getAppId();

        try {
            String encodedFileName = fileName != null
                    ? java.net.URLEncoder.encode(fileName, "UTF-8")
                    : "document";

            String baseUrl = "https://wwo.wps.cn/office/v1/" + appId + "/new";
            String url = String.format(
                    "%s?file_id=%s&_w_tokentype=1&_w_appid=%s&_w_signature=%s&_w_timestamp=%d&_w_file_name=%s&_w_file_type=%s",
                    baseUrl,
                    fileId,
                    appId,
                    signature,
                    timestamp,
                    encodedFileName,
                    getFileType(fileName)
            );

            log.info("Generated WPS edit URL for fileId: {}, mode: {}, signature: {}", fileId, mode, signature);
            return url;
        } catch (java.io.UnsupportedEncodingException e) {
            log.error("Failed to encode fileName: {}", fileName, e);
            String baseUrl = "https://wwo.wps.cn/office/v1/" + appId + "/new";
            return String.format(
                    "%s?file_id=%s&_w_tokentype=1&_w_appid=%s&_w_signature=%s&_w_timestamp=%d&_w_file_name=%s&_w_file_type=%s",
                    baseUrl,
                    fileId,
                    appId,
                    signature,
                    timestamp,
                    fileName != null ? fileName : "document",
                    getFileType(fileName)
            );
        }
    }

    /**
     * 根据文件名获取文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null) {
            return "docx";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "docx";
        } else if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "xlsx";
        } else if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "pptx";
        } else if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        return "docx";
    }

    /**
     * 生成签名（URL 直连方案使用）
     * 签名算法：MD5(app_id + file_id + timestamp + app_secret)
     */
    private String generateSignature(String fileId, long timestamp, String mode) {
        String signStr = getAppId() + fileId + timestamp + getAppSecret();
        return SecureUtil.md5(signStr).toUpperCase();
    }

    /**
     * 生成前端 JS SDK 使用的会话 token
     * 说明：token 完全由业务方自定义，这里采用简单的 MD5(appId|fileId|userId|timestamp|appSecret)
     */
    public String generateSessionToken(String fileId, String userId, long timestamp) {
        String uid = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "1780305141";
        String raw = getAppId() + "|" + fileId + "|" + uid + "|" + timestamp + "|" + getAppSecret();
        return SecureUtil.md5(raw).toUpperCase();
    }

    /**
     * 验证回调签名（回调 URL 方案预留）
     */
    public boolean verifyCallbackSignature(String fileId, long timestamp, String signature) {
        String expectedSignature = generateSignature(fileId, timestamp, "callback");
        return expectedSignature.equalsIgnoreCase(signature);
    }
}
