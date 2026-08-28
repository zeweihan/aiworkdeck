package com.checkba.service.mobile;

import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 云中转区（spec：aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md）契约：
 * - 目录按 (userId, deviceId) 整批替换，跨设备并集按更新时间倒序；
 * - 影像入库幂等键 (userId, clientMediaId)，重复上传返回既有记录、不重写 blob；
 * - clientMediaId 只收 UUID 形态（路径穿越围栏）；
 * - ACK：置 deliveredAt + 立即删 blob，行保留供 status 查询；
 * - status：delivered 与等待秒数，未投递件带 expiresAt；
 * - 30 天 TTL 清理：删行 + 删残留 blob（ACK 是主机制，TTL 只是兜底）；
 * - 3GB 每用户配额：只计未投递 blob，ACK 即释放（dev-board#226）。
 *
 * 内存 H2（MODE=PostgreSQL）约定同 WorkSessionRepositoryTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-relay-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MobileRelayStoreServiceTest {

    @Autowired
    private MobileProjectDirRepository dirRepository;
    @Autowired
    private MobileMediaInboxRepository inboxRepository;

    @TempDir
    Path blobRoot;

    private MobileRelayStoreService service;

    private static final String MEDIA_ID = "0a1b2c3d-1111-4222-8333-444455556666";

    @BeforeEach
    void setUp() {
        service = new MobileRelayStoreService(dirRepository, inboxRepository,
                new MobileRelayLocalBlobStore(blobRoot.toString()));
        // storeMedia 撞唯一约束时的重试经 self 转发到 storeMediaTx（新事务）；生产环境里 self
        // 是 Spring 注入的 @Lazy 代理，这里手工 new 没有容器，直接把 service 自己接上去——
        // 写法与理由同 ProjectProfileServiceTest。
        service.self = service;
    }

    private MobileMediaInbox store(String clientMediaId, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.storeMedia(1L, "dev-a", "42", clientMediaId,
                "IMG_0001.jpg", "image", null, bytes.length,
                new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("目录整批替换：同设备重推覆盖，跨设备并集保留")
    void directoryReplaceIsPerDevice() {
        service.replaceDirectory(1L, "dev-a", "Mac", List.of(
                new MobileRelayStoreService.DirEntry("42", "金冠纾困"),
                new MobileRelayStoreService.DirEntry("43", "probe")));
        service.replaceDirectory(1L, "dev-b", "Win", List.of(
                new MobileRelayStoreService.DirEntry("42", "另一台机的 42")));
        // dev-a 重推：43 没了，44 出现
        service.replaceDirectory(1L, "dev-a", "Mac", List.of(
                new MobileRelayStoreService.DirEntry("42", "金冠纾困"),
                new MobileRelayStoreService.DirEntry("44", "新项目")));

        List<Map<String, Object>> all = service.listDirectory(1L);
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(m -> "44".equals(m.get("key")) && "dev-a".equals(m.get("deviceId"))));
        assertTrue(all.stream().anyMatch(m -> "42".equals(m.get("key")) && "dev-b".equals(m.get("deviceId"))));
        assertFalse(all.stream().anyMatch(m -> "43".equals(m.get("key"))), "dev-a 重推后 43 应被替换掉");
        // 别的用户看不到
        assertTrue(service.listDirectory(2L).isEmpty());
    }

    /**
     * 尽调模块 P3 稳定性余项 #5（dev-board#100）：目录条数超过 MAX_DIR_ENTRIES（1000）
     * 时，旧实现直接抛 IllegalArgumentException——桌面端 pushDirectory 整批推送失败，
     * 一条项目都进不了库，且客户端只把这次失败按普通网络故障 log.warn 一句（律师根本
     * 看不到桌面日志），此后每 10 分钟原样重推同一份超限清单，永远同样失败、永远无声。
     * 改成与 P0 修 MAX_IMPORT_ENTRIES 一致的口径：截断到上限、明确返回截断信息，不
     * 拒绝整批请求——律师至少能同步到最近的 1000 个项目，而不是一个都同步不到。
     */
    @Test
    @DisplayName("目录条数超过上限：不再整批拒绝，截断到上限并如实报告截断信息")
    void directoryOverLimitIsTruncatedNotRejected() {
        List<MobileRelayStoreService.DirEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 1005; i++) {
            entries.add(new MobileRelayStoreService.DirEntry(String.valueOf(i), "project-" + i));
        }

        MobileRelayStoreService.DirectoryReplaceResult result =
                service.replaceDirectory(1L, "dev-a", "Mac", entries);

        assertTrue(result.truncated(), "超过上限必须如实报告 truncated=true，不能装作全部存下了");
        assertEquals(1005, result.totalCount(), "totalCount 要是调用方传入的真实总数");
        assertEquals(1000, result.storedCount(), "storedCount 应该等于上限，不多不少");
        assertEquals(1000, service.listDirectory(1L).size(),
            "数据库里实际存下的行数必须等于上限——截断而不是静默丢弃或异常拒绝整批");
    }

    @Test
    @DisplayName("目录条数不超上限：truncated=false，行为与旧版一致")
    void directoryWithinLimitIsNotTruncated() {
        List<MobileRelayStoreService.DirEntry> entries = List.of(
                new MobileRelayStoreService.DirEntry("42", "金冠纾困"),
                new MobileRelayStoreService.DirEntry("43", "probe"));

        MobileRelayStoreService.DirectoryReplaceResult result =
                service.replaceDirectory(1L, "dev-a", "Mac", entries);

        assertFalse(result.truncated());
        assertEquals(2, result.totalCount());
        assertEquals(2, result.storedCount());
        assertEquals(2, service.listDirectory(1L).size());
    }

    @Test
    @DisplayName("影像入库幂等：同 clientMediaId 重传返回既有记录，blob 不被改写")
    void mediaStoreIsIdempotent() throws Exception {
        MobileMediaInbox first = store(MEDIA_ID, "original-bytes");
        MobileMediaInbox again = store(MEDIA_ID, "different-bytes");
        assertEquals(first.getId(), again.getId());
        assertEquals("original-bytes",
                Files.readString(Path.of(first.getStoragePath()), StandardCharsets.UTF_8));
        assertEquals(1, inboxRepository.count());
    }

    @Test
    @DisplayName("clientMediaId 非 UUID 形态一律拒绝（路径穿越围栏）")
    void mediaIdMustLookLikeUuid() {
        assertThrows(IllegalArgumentException.class, () -> store("../../etc/passwd", "x"));
        assertThrows(IllegalArgumentException.class, () -> store("", "x"));
        assertThrows(IllegalArgumentException.class, () -> store("id with spaces", "x"));
    }

    @Test
    @DisplayName("取件按 (userId, deviceId) 过滤且只给未投递的；ACK 置 deliveredAt 并立即删 blob")
    void inboxAndAck() {
        MobileMediaInbox item = store(MEDIA_ID, "payload");
        assertEquals(1, service.pendingForDevice(1L, "dev-a").size());
        assertTrue(service.pendingForDevice(1L, "dev-b").isEmpty());
        assertTrue(service.pendingForDevice(2L, "dev-a").isEmpty());

        Path blob = Path.of(item.getStoragePath());
        assertTrue(Files.exists(blob));
        service.ack(1L, item.getId());
        assertFalse(Files.exists(blob), "ACK 必须立即删除 blob");
        assertTrue(service.pendingForDevice(1L, "dev-a").isEmpty());

        // 行保留，status 可查
        List<Map<String, Object>> status = service.status(1L, List.of(MEDIA_ID));
        assertEquals(1, status.size());
        assertEquals(Boolean.TRUE, status.get(0).get("delivered"));
    }

    @Test
    @DisplayName("ACK 只认属主：他人 id 拒绝")
    void ackChecksOwnership() {
        MobileMediaInbox item = store(MEDIA_ID, "payload");
        assertThrows(IllegalArgumentException.class, () -> service.ack(2L, item.getId()));
    }

    @Test
    @DisplayName("status：未投递的 delivered=false 且带等待秒数；未知 id 不出现")
    void statusReportsWaiting() {
        store(MEDIA_ID, "payload");
        List<Map<String, Object>> status = service.status(1L, List.of(MEDIA_ID, "ffffffff-0000-4000-8000-000000000000"));
        assertEquals(1, status.size());
        assertEquals(Boolean.FALSE, status.get(0).get("delivered"));
        assertTrue((long) status.get(0).get("waitingSeconds") >= 0);
    }

    @Test
    @DisplayName("30 天 TTL 兜底：过期行删除，未投递的残留 blob 一并删；未过期的不动")
    void ttlCleanupRemovesRowsAndOrphanBlobs() {
        MobileMediaInbox item = store(MEDIA_ID, "payload");
        // 8 天前：7 天 TTL 时代会被清掉，30 天口径下必须还在（dev-board#226 延长的意义所在）
        item.setCreatedAt(LocalDateTime.now().minusDays(8));
        inboxRepository.save(item);
        Path blob = Path.of(item.getStoragePath());
        assertTrue(Files.exists(blob));

        service.cleanupExpired();
        assertTrue(Files.exists(blob), "8 天的件在 30 天 TTL 下不该被清");
        assertEquals(1, inboxRepository.count());

        item.setCreatedAt(LocalDateTime.now().minusDays(31));
        inboxRepository.save(item);
        service.cleanupExpired();
        assertFalse(Files.exists(blob));
        assertEquals(0, inboxRepository.count());
    }

    @Test
    @DisplayName("mediaType 收 audio（手机录音走同一条中转链路），其余类型拒绝")
    void audioMediaTypeIsAccepted() {
        MobileMediaInbox item = service.storeMedia(1L, "dev-a", "42", MEDIA_ID,
                "REC_0001.m4a", "audio", null, 7,
                new ByteArrayInputStream("audio-x".getBytes(StandardCharsets.UTF_8)));
        assertEquals("audio", item.getMediaType());
        assertThrows(IllegalArgumentException.class, () -> service.storeMedia(
                1L, "dev-a", "42", "0a1b2c3d-1111-4222-8333-444455557777",
                "x.bin", "binary", null, 1,
                new ByteArrayInputStream(new byte[]{1})));
    }

    @Test
    @DisplayName("status：未投递件带 expiresAt（createdAt+TTL），已投递件不带")
    void statusCarriesExpiresAtForUndelivered() {
        MobileMediaInbox item = store(MEDIA_ID, "payload");
        Map<String, Object> pending = service.status(1L, List.of(MEDIA_ID)).get(0);
        assertEquals(item.getCreatedAt().plus(MobileRelayStoreService.TTL).toString(),
                pending.get("expiresAt"));

        service.ack(1L, item.getId());
        Map<String, Object> delivered = service.status(1L, List.of(MEDIA_ID)).get(0);
        assertFalse(delivered.containsKey("expiresAt"), "已投递件没有到期概念");
    }

    @Test
    @DisplayName("配额：未投递 blob 占满 3GB 后拒绝新上传，ACK 释放后恢复；重传不受配额影响")
    void quotaBlocksNewUploadsAndAckFrees() throws Exception {
        // 直接造一行占满配额的未投递件（不真写 3GB 字节）
        Path bigBlob = blobRoot.resolve("1").resolve("big-blob");
        Files.createDirectories(bigBlob.getParent());
        Files.writeString(bigBlob, "placeholder");
        MobileMediaInbox big = new MobileMediaInbox();
        big.setUserId(1L);
        big.setDeviceId("dev-a");
        big.setProjectKey("42");
        big.setClientMediaId("ffffffff-0000-4000-8000-00000000aaaa");
        big.setFileName("huge.mov");
        big.setMediaType("video");
        big.setFileSize(MobileRelayStoreService.QUOTA_BYTES);
        big.setStoragePath(bigBlob.toString());
        big.setCreatedAt(LocalDateTime.now());
        inboxRepository.saveAndFlush(big);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store(MEDIA_ID, "one-more-byte"));
        assertTrue(e.getMessage().contains("云端空间已满"), "配额拒绝要给用户可读的原因，实际: " + e.getMessage());

        // 同 clientMediaId 的重传是幂等命中，配额满也不能拒（不占新空间）
        MobileMediaInbox again = service.storeMedia(1L, "dev-a", "42", big.getClientMediaId(),
                "huge.mov", "video", null, 3, new ByteArrayInputStream("xxx".getBytes(StandardCharsets.UTF_8)));
        assertEquals(big.getId(), again.getId());

        // 别的用户不受影响
        MobileMediaInbox other = service.storeMedia(2L, "dev-z", "1", MEDIA_ID,
                "IMG.jpg", "image", null, 3, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(other.getId());

        // ACK 即删 blob = 释放配额，循环利用
        service.ack(1L, big.getId());
        MobileMediaInbox landed = store(MEDIA_ID, "fits-now");
        assertNotNull(landed.getId());
    }

    @Test
    @DisplayName("usage：只计未投递 blob 的字节数，带配额上限")
    void usageCountsOnlyPendingBlobs() {
        MobileMediaInbox item = store(MEDIA_ID, "12345");
        Map<String, Object> usage = service.usage(1L);
        assertEquals(5L, usage.get("usedBytes"));
        assertEquals(MobileRelayStoreService.QUOTA_BYTES, usage.get("quotaBytes"));

        service.ack(1L, item.getId());
        assertEquals(0L, service.usage(1L).get("usedBytes"), "ACK 之后配额应释放");
    }
}
