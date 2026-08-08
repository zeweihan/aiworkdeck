package com.checkba.service;

import com.checkba.model.entity.UserSession;
import com.checkba.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DB 落库会话的服务层语义：哈希落库（明文不进库）、滑动过期、lastUsedAt 写回节流、
 * 登出删行。AuthController 的接线由 AuthControllerHardeningTest/SmsTest 顺带覆盖。
 */
class UserSessionServiceTest {

    private static UserSession row(Long userId, LocalDateTime lastUsedAt) {
        UserSession s = new UserSession();
        s.setId(1L);
        s.setUserId(userId);
        s.setTokenHash("h");
        s.setCreatedAt(lastUsedAt);
        s.setLastUsedAt(lastUsedAt);
        return s;
    }

    @Test
    @DisplayName("签发：库里存的是 64 位十六进制哈希，不是明文")
    void issueStoresHashNotPlaintext() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo);

        String plaintext = service.issue(7L);

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(repo).save(captor.capture());
        UserSession saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertNotEquals(plaintext, saved.getTokenHash());
        assertTrue(saved.getTokenHash().matches("[0-9a-f]{64}"));
        assertNotNull(saved.getCreatedAt());
        assertEquals(saved.getCreatedAt(), saved.getLastUsedAt());
    }

    @Test
    @DisplayName("解析：命中且未过期返回 userId")
    void resolveReturnsUserId() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        when(repo.findByTokenHash(anyString()))
                .thenReturn(Optional.of(row(7L, LocalDateTime.now())));
        UserSessionService service = new UserSessionService(repo);

        assertEquals(7L, service.resolveUserId("session_abc"));
    }

    @Test
    @DisplayName("滑动过期：超过 7 天未使用的会话解析为 null 并被删除")
    void expiredSessionIsDeleted() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSession stale = row(7L, LocalDateTime.now().minusDays(8));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(stale));
        UserSessionService service = new UserSessionService(repo);

        assertNull(service.resolveUserId("session_abc"));
        verify(repo).delete(stale);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("lastUsedAt 节流：一分钟内的重复请求不写库，超过则写回")
    void touchIsThrottled() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo);

        when(repo.findByTokenHash(anyString()))
                .thenReturn(Optional.of(row(7L, LocalDateTime.now().minusSeconds(10))));
        assertEquals(7L, service.resolveUserId("session_abc"));
        verify(repo, never()).save(any());

        when(repo.findByTokenHash(anyString()))
                .thenReturn(Optional.of(row(7L, LocalDateTime.now().minusMinutes(5))));
        assertEquals(7L, service.resolveUserId("session_abc"));
        verify(repo).save(any());
    }

    @Test
    @DisplayName("非 session_ 前缀（如 awdt_ 设备令牌）不触库直接返回 null")
    void foreignPrefixShortCircuits() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo);

        assertNull(service.resolveUserId("awdt_xyz"));
        assertNull(service.resolveUserId(null));
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("登出：按哈希删行；未知 ID 静默")
    void revokeDeletesRow() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo);

        service.revoke("session_abc");
        verify(repo).deleteByTokenHash(anyString());

        service.revoke(null);
        service.revoke("awdt_xyz");
        verifyNoMoreInteractions(repo);
    }

    @Test
    @DisplayName("定时清理：按 lastUsedAt 截止时间批量删除")
    void purgeDeletesByCutoff() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        when(repo.deleteByLastUsedAtBefore(any())).thenReturn(3L);
        UserSessionService service = new UserSessionService(repo);

        service.purgeExpired();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteByLastUsedAtBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        assertTrue(cutoff.isBefore(LocalDateTime.now().minusDays(6)));
        assertTrue(cutoff.isAfter(LocalDateTime.now().minusDays(8)));
    }
}
