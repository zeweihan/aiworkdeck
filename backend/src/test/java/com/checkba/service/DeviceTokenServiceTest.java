package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceTokenServiceTest {

    private DeviceTokenRepository repo;
    private DeviceTokenService svc;
    private final Map<String, DeviceToken> byHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        // 本类不走 Spring TestContext，StaticAuthPointerResetListener 覆盖不到：
        // 先跑的 local-mode=true 上下文可能把 static 指针钉在别人的 LocalIdentityService 上，
        // getUserIdFromSession(null) 会被解析成本机用户而不是 null。钉回一个关着
        // local-mode 的实例（localMode=false 时构造器不触碰任何仓库，传 null 安全）。
        AuthController.registerLocalIdentityService(
                new LocalIdentityService(null, null, null, null, false));
        byHash.clear();
        repo = mock(DeviceTokenRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            byHash.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0, String.class))));
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(0, Long.class);
                    return byHash.values().stream()
                            .filter(t -> id.equals(t.getId()))
                            .findFirst();
                });
        doAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            byHash.remove(t.getTokenHash());
            return null;
        }).when(repo).delete(any());
        when(repo.findByUserIdOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(inv -> {
                    Long userId = inv.getArgument(0, Long.class);
                    return byHash.values().stream()
                            .filter(t -> userId.equals(t.getUserId()))
                            .sorted(Comparator.comparing(DeviceToken::getCreatedAt).reversed())
                            .toList();
                });
        svc = new DeviceTokenService(repo);
    }

    @Test
    void issuedTokenResolvesBackToUser() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook");
        assertTrue(issued.plaintext().startsWith(DeviceTokenService.TOKEN_PREFIX));
        assertEquals(42L, svc.resolveUserId(issued.plaintext()));
    }

    @Test
    void plaintextIsNeverStored() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook");
        assertTrue(byHash.values().stream()
                .noneMatch(t -> issued.plaintext().equals(t.getTokenHash())));
        assertNull(svc.resolveUserId(DeviceTokenService.TOKEN_PREFIX + "wrong"));
    }

    @Test
    void staticAuthEntryRecognisesTokens() {
        // DeviceTokenService 构造器把自己注册进 AuthController 静态入口
        DeviceTokenService.IssuedToken issued = svc.issue(7L, "e2e");
        assertEquals(7L, AuthController.getUserIdFromSession(issued.plaintext()));
        assertNull(AuthController.getUserIdFromSession(null)); // null 守卫，不再 NPE
    }

    @Test
    void revokeDeletesOwnToken() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook");
        svc.revoke(42L, issued.id());
        assertNull(svc.resolveUserId(issued.plaintext()));
    }

    @Test
    void revokeIgnoresTokensOfOthers() {
        DeviceTokenService.IssuedToken issuedB = svc.issue(2L, "B's phone");
        svc.revoke(1L, issuedB.id());
        assertEquals(2L, svc.resolveUserId(issuedB.plaintext()));
    }

    @Test
    void listMineReturnsOnlyOwnTokens() {
        svc.issue(1L, "A's laptop");
        DeviceTokenService.IssuedToken issuedB = svc.issue(2L, "B's phone");

        List<DeviceToken> mineOfB = svc.listMine(2L);

        assertEquals(1, mineOfB.size());
        assertEquals(issuedB.id(), mineOfB.get(0).getId());
    }
}
