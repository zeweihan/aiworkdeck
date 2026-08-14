package com.checkba.service.meeting;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.Date;

/** 阿里云 OSS SDK 实现。客户端按调用创建、用完关闭——转写是低频操作，不值得池化。 */
@Slf4j
@Component
public class MeetingOssClientImpl implements MeetingOssClient {

    @Override
    public String uploadAndSign(MeetingAsrSettings settings, String objectKey, File file, Duration urlTtl)
            throws Exception {
        OSS oss = new OSSClientBuilder().build(
                settings.ossEndpoint(), settings.accessKeyId(), settings.accessKeySecret());
        try {
            oss.putObject(settings.ossBucket(), objectKey, file);
            Date expiration = new Date(System.currentTimeMillis() + urlTtl.toMillis());
            URL url = oss.generatePresignedUrl(settings.ossBucket(), objectKey, expiration);
            return url.toString();
        } finally {
            oss.shutdown();
        }
    }

    @Override
    public void deleteQuietly(MeetingAsrSettings settings, String objectKey) {
        try {
            OSS oss = new OSSClientBuilder().build(
                    settings.ossEndpoint(), settings.accessKeyId(), settings.accessKeySecret());
            try {
                oss.deleteObject(settings.ossBucket(), objectKey);
            } finally {
                oss.shutdown();
            }
        } catch (Exception e) {
            log.warn("删除 OSS 中转音频失败（不影响转写结果）: {}", e.toString());
        }
    }
}
