package com.checkba.service.meeting;

/**
 * 会议转写所需的阿里云凭证五件套（通义听悟 + OSS）。
 * 值来自 SystemSettingService（管理页可改）与 env/yml 默认值两级，见 MeetingTranscriptionService。
 */
public record MeetingAsrSettings(
        String accessKeyId,
        String accessKeySecret,
        String appKey,
        String ossBucket,
        String ossEndpoint
) {
    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    /** 五项齐全才算配置完成；缺任何一项都走「仅录音」降级路径。 */
    public boolean configured() {
        return has(accessKeyId) && has(accessKeySecret) && has(appKey) && has(ossBucket) && has(ossEndpoint);
    }
}
