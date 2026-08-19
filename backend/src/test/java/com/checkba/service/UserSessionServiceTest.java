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
 * 滑动过期天数自 security.session-idle-days 可配：代码默认 365（桌面「常驻」），
 * 云端 application-cloud.yml 显式配 7 保持已上线契约。
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
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

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
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

        assertEquals(7L, service.resolveUserId("session_abc"));
    }

    @Test
    @DisplayName("滑动过期：按配置天数（云端配 7）闲置超过 7 天的会话解析为 null 并被删除")
    void expiredSessionIsDeleted() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSession stale = row(7L, LocalDateTime.now().minusDays(8));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(stale));
        UserSessionService service = new UserSessionService(repo, 7);

        assertNull(service.resolveUserId("session_abc"));
        verify(repo).delete(stale);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("桌面默认 365 天：闲置 8 天照常有效（常驻语义），闲置超过一年才过期")
    void desktopDefaultKeepsSessionForAYear() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

        // 云端契约下必掉线的「闲置 8 天」，在默认 365 天下必须仍然有效
        when(repo.findByTokenHash(anyString()))
                .thenReturn(Optional.of(row(7L, LocalDateTime.now().minusDays(8))));
        assertEquals(7L, service.resolveUserId("session_abc"));
        verify(repo, never()).delete(any(UserSession.class));

        // 超过一年才失效（常驻不等于永久：被盗凭据仍有终点）
        UserSession ancient = row(7L, LocalDateTime.now().minusDays(366));
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(ancient));
        assertNull(service.resolveUserId("session_abc"));
        verify(repo).delete(ancient);
    }

    @Test
    @DisplayName("代码默认值钉在 365：@Value 注解默认值与 DEFAULT_IDLE_DAYS 一致")
    void codeDefaultIsPinnedTo365() throws Exception {
        assertEquals(365, UserSessionService.DEFAULT_IDLE_DAYS);
        var ctor = UserSessionService.class.getConstructor(
                com.checkba.repository.UserSessionRepository.class, long.class);
        var value = ctor.getParameters()[1]
                .getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertNotNull(value, "空闲天数参数必须带 @Value，否则配置接不进来");
        assertEquals("${security.session-idle-days:365}", value.value());
    }

    @Test
    @DisplayName("云端契约：application-cloud.yml 显式钉住 session-idle-days: 7，不跟默认值漂移")
    void cloudProfilePinsSevenDays() throws Exception {
        try (var in = UserSessionServiceTest.class.getResourceAsStream("/application-cloud.yml")) {
            assertNotNull(in, "application-cloud.yml 必须在 classpath 上");
            String yml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(yml.contains("session-idle-days: 7"),
                    "云端「7 天滑动过期」是已上线契约：默认值改成 365 后必须在 cloud profile 显式配 7");
        }
    }

    @Test
    @DisplayName("lastUsedAt 节流：一分钟内的重复请求不写库，超过则写回")
    void touchIsThrottled() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

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
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

        assertNull(service.resolveUserId("awdt_xyz"));
        assertNull(service.resolveUserId(null));
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("登出：按哈希删行；未知 ID 静默")
    void revokeDeletesRow() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        UserSessionService service = new UserSessionService(repo, UserSessionService.DEFAULT_IDLE_DAYS);

        service.revoke("session_abc");
        verify(repo).deleteByTokenHash(anyString());

        service.revoke(null);
        service.revoke("awdt_xyz");
        verifyNoMoreInteractions(repo);
    }

    @Test
    @DisplayName("定时清理：按配置的空闲天数推截止时间批量删除")
    void purgeDeletesByCutoff() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        when(repo.deleteByLastUsedAtBefore(any())).thenReturn(3L);
        UserSessionService service = new UserSessionService(repo, 7);

        service.purgeExpired();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteByLastUsedAtBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        assertTrue(cutoff.isBefore(LocalDateTime.now().minusDays(6)));
        assertTrue(cutoff.isAfter(LocalDateTime.now().minusDays(8)));
    }
}
