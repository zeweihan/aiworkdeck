package com.checkba.service.meeting;

import java.io.File;
import java.time.Duration;

/**
 * 会议音频的 OSS 中转抽象：听悟只收公网 URL，所以音频先上私有 bucket、
 * 签一个限时 URL 交给听悟，转写完成即删。测试用桩的接缝。
 */
public interface MeetingOssClient {

    /** 上传文件并返回签名 URL（听悟要求 URL 有效期 >= 3 小时）。 */
    String uploadAndSign(MeetingAsrSettings settings, String objectKey, File file, Duration urlTtl) throws Exception;

    /** 删除中转对象。失败只记日志——删除是清理动作，不能影响转写结果。 */
    void deleteQuietly(MeetingAsrSettings settings, String objectKey);
}
