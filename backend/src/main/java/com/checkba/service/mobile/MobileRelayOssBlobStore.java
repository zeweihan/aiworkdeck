package com.checkba.service.mobile;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OSS 实现（dev-board#236）：中转 blob 落平台自己的私有桶，locator = object key
 * （{keyPrefix}{userId}/{clientMediaId}）。
 *
 * <p>与 {@code MeetingOssClientImpl} 的刻意分歧：那边按调用建客户端（低频转写），
 * 这边上传/下载/删除是持续流量，客户端做成单例、由配置类负责 shutdown。
 *
 * <p>迁移期双读：存量行的 storagePath 是老的本地绝对路径（以 {@code /} 开头），
 * 读/删原样走文件系统；这些行最迟 30 天被 ACK 或 TTL 消化掉，之后这个分支就是死代码，
 * 但删它不值得冒险。
 */
@Slf4j
public final class MobileRelayOssBlobStore implements MobileRelayBlobStore, AutoCloseable {

    private final OSS oss;
    private final String bucket;
    private final String keyPrefix;

    public MobileRelayOssBlobStore(OSS oss, String bucket, String keyPrefix) {
        this.oss = oss;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public StoredBlob put(Long userId, String clientMediaId, InputStream content, long declaredSize) {
        String key = keyPrefix + userId + "/" + clientMediaId;
        CountingInputStream counted = new CountingInputStream(content);
        ObjectMetadata meta = new ObjectMetadata();
        if (declaredSize > 0) {
            // 有 Content-Length SDK 才流式直传；不设会全量缓冲进堆（nginx 单请求 200MB，缓冲不起）
            meta.setContentLength(declaredSize);
        }
        oss.putObject(bucket, key, counted, meta);
        return new StoredBlob(key, counted.count);
    }

    @Override
    public InputStream open(String locator) throws IOException {
        if (isLegacyLocalPath(locator)) {
            return Files.newInputStream(Path.of(locator));
        }
        return oss.getObject(bucket, locator).getObjectContent();
    }

    @Override
    public boolean exists(String locator) {
        if (isLegacyLocalPath(locator)) {
            return Files.exists(Path.of(locator));
        }
        return oss.doesObjectExist(bucket, locator);
    }

    @Override
    public void deleteQuietly(String locator) {
        try {
            if (isLegacyLocalPath(locator)) {
                Files.deleteIfExists(Path.of(locator));
            } else {
                oss.deleteObject(bucket, locator);
            }
        } catch (Exception e) {
            // OSSException/ClientException 是 RuntimeException：catch-all 才保得住
            // 「删不掉不挡 ACK」的语义（形状照抄 MeetingOssClientImpl.deleteQuietly）
            log.warn("删除中转 blob 失败，留给桶生命周期兜底: {}", locator, e);
        }
    }

    private static boolean isLegacyLocalPath(String locator) {
        return locator.startsWith("/");
    }

    @Override
    public void close() {
        oss.shutdown();
    }

    /** put 需要真实写入字节数做配额账本，SDK 不回传，只能自己数。 */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
