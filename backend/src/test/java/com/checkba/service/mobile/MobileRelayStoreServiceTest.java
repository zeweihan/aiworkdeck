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
 * - status：delivered 与等待秒数；
 * - 7 天 TTL 清理：删行 + 删残留 blob（ACK 是主机制，TTL 只是兜底）。
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
        service = new MobileRelayStoreService(dirRepository, inboxRepository, blobRoot.toString());
        // storeMedia 撞唯一约束时的重试经 self 转发到 storeMediaTx（新事务）；生产环境里 self
        // 是 Spring 注入的 @Lazy 代理，这里手工 new 没有容器，直接把 service 自己接上去——
        // 写法与理由同 ProjectProfileServiceTest。
        service.self = service;
    }

    private MobileMediaInbox store(String clientMediaId, String content) {
        return service.storeMedia(1L, "dev-a", "42", clientMediaId,
                "IMG_0001.jpg", "image", null,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
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
    @DisplayName("7 天 TTL 兜底：过期行删除，未投递的残留 blob 一并删")
    void ttlCleanupRemovesRowsAndOrphanBlobs() {
        MobileMediaInbox item = store(MEDIA_ID, "payload");
        item.setCreatedAt(LocalDateTime.now().minusDays(8));
        inboxRepository.save(item);
        Path blob = Path.of(item.getStoragePath());
        assertTrue(Files.exists(blob));

        service.cleanupExpired();
        assertFalse(Files.exists(blob));
        assertEquals(0, inboxRepository.count());
    }
}
