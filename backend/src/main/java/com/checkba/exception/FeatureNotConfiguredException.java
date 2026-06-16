package com.checkba.exception;

/**
 * 功能未配置异常 / Feature-not-configured exception.
 *
 * <p>当某个可选功能（OCR / TTS / WPS 文档编辑等）所需的密钥或配置缺失时抛出。
 * 全局异常处理器会把它转成 {@code {code: 4001, feature, message, configured: false}}
 * 的结构化响应（HTTP 200），前端据此引导用户"去设置"而非显示通用报错。
 *
 * <p>Thrown when an optional feature (OCR / TTS / WPS document editing, etc.) is
 * missing its required keys or configuration. The global handler maps it to a
 * recognizable {@code {code: 4001, feature, ...}} response so the frontend can
 * prompt the user to configure it instead of surfacing a generic error.
 */
public class FeatureNotConfiguredException extends RuntimeException {

    /** 功能标识，如 "ocr" / "tts" / "wps"，供前端定位设置项。 */
    private final String feature;

    public FeatureNotConfiguredException(String feature, String message) {
        super(message);
        this.feature = feature;
    }

    public String getFeature() {
        return feature;
    }
}
