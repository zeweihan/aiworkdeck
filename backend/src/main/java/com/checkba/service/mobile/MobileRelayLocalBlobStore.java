package com.checkba.service.mobile;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地盘实现：行为与抽象前的 MobileRelayStoreService 逐字一致
 * （blob 落 {root}/mobile-relay/{userId}/{clientMediaId}，locator = 绝对路径）。
 * desktop/prod profile 与全部测试默认走它。
 */
@Slf4j
public final class MobileRelayLocalBlobStore implements MobileRelayBlobStore {

    private final Path relayRoot;

    public MobileRelayLocalBlobStore(String storageRoot) {
        this.relayRoot = Path.of(storageRoot, "mobile-relay").toAbsolutePath().normalize();
    }

    @Override
    public StoredBlob put(Long userId, String clientMediaId, InputStream content, long declaredSize) {
        Path blob = relayRoot.resolve(String.valueOf(userId)).resolve(clientMediaId);
        try {
            Files.createDirectories(blob.getParent());
            long size = Files.copy(content, blob, StandardCopyOption.REPLACE_EXISTING);
            return new StoredBlob(blob.toString(), size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(String locator) throws IOException {
        return Files.newInputStream(Path.of(locator));
    }

    @Override
    public boolean exists(String locator) {
        return Files.exists(Path.of(locator));
    }

    @Override
    public void deleteQuietly(String locator) {
        try {
            Files.deleteIfExists(Path.of(locator));
        } catch (IOException e) {
            log.warn("删除中转 blob 失败，留给 TTL/生命周期兜底: {}", locator, e);
        }
    }
}
