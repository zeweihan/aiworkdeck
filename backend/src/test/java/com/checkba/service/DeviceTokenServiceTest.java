package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void resolveUserIdCoalescesLastUsedAtWrites() {
        // 病灶：resolveUserId 每次命中都无条件 save()，而它是每个设备令牌请求的必经之路——
        // 高频轮询（移动端同步中转、activity 上报、编辑器自动保存）把每次读请求都变成一次
        // DB 写。这里验证：同一令牌短时间内连续解析两次，只应该多落一次库（节流生效），
        // 而不是每次都写。
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook"); // save #1：签发
        svc.resolveUserId(issued.plaintext()); // lastUsedAt 此前是 null，必然落库一次：save #2
        svc.resolveUserId(issued.plaintext()); // 紧接着再解析一次：节流窗口内，不该再落库

        verify(repo, times(2)).save(any());
    }

    @Test
    void purgeIdleTokensDeletesRowsOlderThan365Days() {
        // 病灶：device_token 没有任何过期/清理机制——同一 auth 子系统里 UserSessionService
        // 有 @Scheduled 每日清理过期会话，device_token 没有。签发了忘记撤销的令牌永久留在
        // 库里。365 天与 UserSessionService 桌面档 security.session-idle-days 默认值一致：
        // 设备令牌是「长期配对」语义，不该比会话更容易被清掉。
        when(repo.deleteIdleBefore(any())).thenReturn(3);

        svc.purgeIdleTokens();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteIdleBefore(captor.capture());
        long daysAgo = java.time.Duration.between(captor.getValue(), LocalDateTime.now()).toDays();
        assertTrue(daysAgo >= 364 && daysAgo <= 365,
                "空转清理窗口应为 365 天左右，实际传入的 cutoff 是 " + daysAgo + " 天前");
    }

    @Test
    void listMineReturnsOnlyOwnTokens() {
        svc.issue(1L, "A's laptop");
        DeviceTokenService.IssuedToken issuedB = svc.issue(2L, "B's phone");

        List<DeviceToken> mineOfB = svc.listMine(2L);

        assertEquals(1, mineOfB.size());
        assertEquals(issuedB.id(), mineOfB.get(0).getId());
    }

    // ==== 修复：resolveUserId 原来每个设备令牌请求都无条件写一次库（SELECT+UPDATE），
    // 云端协作客户端的每次轮询/读接口都被打成一次写。节流到「一分钟内不重复落盘」，
    // 与同一份代码里 UserSessionService 的 TOUCH_INTERVAL 节流手法一致。

    @Test
    void resolveUserIdThrottlesRepeatedLastUsedAtWrites() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook"); // save #1：签发本身要落库

        // 节流窗口内连续解析三次，应该只在首次（lastUsedAt 从未写过）补一次 save，
        // 之后的重复请求不能再逐请求写库。
        assertEquals(42L, svc.resolveUserId(issued.plaintext()));
        assertEquals(42L, svc.resolveUserId(issued.plaintext()));
        assertEquals(42L, svc.resolveUserId(issued.plaintext()));

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void resolveUserIdWritesAgainAfterThrottleWindowElapses() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook"); // save #1
        svc.resolveUserId(issued.plaintext()); // save #2：lastUsedAt 首次从 null 写入

        DeviceToken token = byHash.values().iterator().next();
        // 手工把 lastUsedAt 拨回节流窗口之外，模拟「一分钟后又来了一次请求」。
        token.setLastUsedAt(token.getLastUsedAt().minus(DeviceTokenService.TOUCH_INTERVAL).minusSeconds(1));

        assertEquals(42L, svc.resolveUserId(issued.plaintext())); // save #3：窗口已过，应重新写

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(3)).save(any());
    }
}
