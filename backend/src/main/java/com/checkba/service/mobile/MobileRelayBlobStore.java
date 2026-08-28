package com.checkba.service.mobile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 中转区 blob 的存取接缝（模式照抄 {@code MeetingOssClient}：接口的存在理由是测试用桩）。
 *
 * <p>locator 是 {@code MobileMediaInbox.storagePath} 里存的定位符：本地实现 = 绝对路径，
 * OSS 实现 = object key。它的第二重身份（非空 = 占配额）由 {@link MobileRelayStoreService}
 * 维护，本接口不感知。
 */
public interface MobileRelayBlobStore {

    /** put 的结果：写进 storagePath 的定位符 + 实际写入字节数（配额账本的口径）。 */
    record StoredBlob(String locator, long size) {}

    /**
     * 写入一件 blob。失败抛未检查异常，由调用方翻译成用户可见的「影像暂存失败」。
     *
     * @param declaredSize controller 传 MultipartFile.getSize()，即实际字节数；
     *                     OSS 实现用它做 Content-Length 避免全量缓冲
     */
    StoredBlob put(Long userId, String clientMediaId, InputStream content, long declaredSize);

    /** 打开内容流。调用方负责关闭。 */
    InputStream open(String locator) throws IOException;

    boolean exists(String locator);

    /** 删除失败只 warn 绝不抛——blob 删不掉不该挡住 ACK/TTL 的行级操作。 */
    void deleteQuietly(String locator);
}
