package com.checkba.service.mobile;

import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 中转 blob 存储的装配（dev-board#236）。本仓不用 @ConditionalOnProperty，
 * 按配置在工厂方法里二选一。
 *
 * <p>开关与凭证全走环境变量（MOBILE_RELAY_OSS_*，注入点 /opt/aiworkdeck/cloud/env）：
 * 云后端配齐即走 OSS；desktop/团队服务器不配，维持本地盘，行为与迁移前逐字一致。
 * enabled=true 但配置不全是部署事故，宁可启动即炸也不静默回落本地盘——
 * 回落意味着「以为在 OSS 上其实还在 ECS」，正是这次迁移要消灭的状态。
 */
@Configuration
@Slf4j
public class MobileRelayStorageConfig {

    @Bean
    public MobileRelayBlobStore mobileRelayBlobStore(
            @Value("${mobile.relay.oss.enabled:false}") boolean ossEnabled,
            @Value("${mobile.relay.oss.endpoint:}") String endpoint,
            @Value("${mobile.relay.oss.bucket:}") String bucket,
            @Value("${mobile.relay.oss.access-key-id:}") String accessKeyId,
            @Value("${mobile.relay.oss.access-key-secret:}") String accessKeySecret,
            @Value("${mobile.relay.oss.key-prefix:mobile-relay/}") String keyPrefix,
            @Value("${storage.local.root-path:data}") String storageRoot) {
        if (!ossEnabled) {
            return new MobileRelayLocalBlobStore(storageRoot);
        }
        if (endpoint.isBlank() || bucket.isBlank() || accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                    "mobile.relay.oss.enabled=true 但 endpoint/bucket/AK 不完整——"
                    + "检查 MOBILE_RELAY_OSS_ENDPOINT / MOBILE_RELAY_OSS_BUCKET / "
                    + "MOBILE_RELAY_OSS_AK_ID / MOBILE_RELAY_OSS_AK_SECRET");
        }
        log.info("影像中转 blob 走 OSS: bucket={}, endpoint={}", bucket, endpoint);
        return new MobileRelayOssBlobStore(
                new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret), bucket, keyPrefix);
    }
}
