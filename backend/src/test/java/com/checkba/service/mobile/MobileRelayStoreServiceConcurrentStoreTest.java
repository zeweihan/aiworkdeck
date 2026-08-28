package com.checkba.service.mobile;

import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.repository.MobileDeviceStateRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 装配风格对齐 {@code com.checkba.service.ProjectProfileServiceTest}：手工 mock + 手工
 * new + 手动接 self（没有 Spring 容器，@Transactional 在这里本就是空注解，只验证 Java
 * 层的控制流：撞约束之后是不是真的回落到既有记录，而不是让异常逃出 storeMedia）。
 *
 * <p>覆盖的是 storeMedia 先查后插之间没有锁的 TOCTOU 缺口：两次并发重传各自查到
 * "不存在"，先落库的一方成功，落败的一方撞 (userId, clientMediaId) 唯一约束抛
 * DataIntegrityViolationException——用 Mockito 的连续 stub 模拟这个时序，不需要真起线程。
 */
class MobileRelayStoreServiceConcurrentStoreTest {

    private static final String MEDIA_ID = "0a1b2c3d-1111-4222-8333-444455556666";

    private MobileMediaInboxRepository inboxRepository;
    private MobileProjectDirRepository dirRepository;
    private MobileRelayStoreService service;

    @TempDir
    Path blobRoot;

    @BeforeEach
    void setUp() {
        dirRepository = mock(MobileProjectDirRepository.class);
        inboxRepository = mock(MobileMediaInboxRepository.class);
        service = new MobileRelayStoreService(dirRepository, inboxRepository,
                mock(MobileDeviceStateRepository.class),
                new MobileRelayLocalBlobStore(blobRoot.toString()));
        service.self = service;
    }

    @Test
    @DisplayName("并发重传撞唯一约束：不让异常逃出去，回落到既有记录")
    void concurrentStoreFallsBackToExistingRecordInsteadOfThrowing() {
        MobileMediaInbox existing = new MobileMediaInbox();
        existing.setId(100L);
        existing.setUserId(1L);
        existing.setClientMediaId(MEDIA_ID);
        existing.setFileName("IMG_0001.jpg");

        // 第一次查：TOCTOU 窗口内，两边都查到"不存在"；重试时的第二次查：对方已提交，查到既有记录
        when(inboxRepository.findByUserIdAndClientMediaId(1L, MEDIA_ID))
                .thenReturn(Optional.empty(), Optional.of(existing));
        // 落库这一刻撞对方已经提交的唯一约束
        when(inboxRepository.save(any(MobileMediaInbox.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: user_id, client_media_id"));

        MobileMediaInbox result = service.storeMedia(1L, "dev-a", "42", MEDIA_ID,
                "IMG_0001.jpg", "image", null, 5,
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals(existing.getId(), result.getId(), "撞约束之后必须回落到既有记录，而不是把异常甩给调用方");
        verify(inboxRepository, times(1)).save(any(MobileMediaInbox.class));
        verify(inboxRepository, times(2)).findByUserIdAndClientMediaId(1L, MEDIA_ID);
    }
}
