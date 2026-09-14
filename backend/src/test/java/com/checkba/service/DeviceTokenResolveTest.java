// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 设备令牌解析的设备维度（spec 2026-09-14 §2.3）。
 *
 * <p>为什么非要 tokenId：同一个官网账号在两台机器上桥接落到**同一行** app_users，
 * 光有 userId 分不出「你在另一台电脑交了稿」和「你自己刚交的稿」。tokenId 是整条
 * git 链路上唯一还认得出「哪一台机器」的东西。
 */
class DeviceTokenResolveTest {

    private final Map<String, DeviceToken> byHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private DeviceTokenService service;

    @BeforeEach
    void setUp() {
        byHash.clear();
        DeviceTokenRepository repository = mock(DeviceTokenRepository.class);
        when(repository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            byHash.put(t.getTokenHash(), t);
            return t;
        });
        when(repository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0, String.class))));
        service = new DeviceTokenService(repository);
    }

    @Test
    @DisplayName("resolve 同时给出人与设备；两枚令牌同一个人，设备 id 不同")
    void resolveCarriesBothUserAndDevice() {
        DeviceTokenService.IssuedToken a = service.issue(42L, "MacBook Pro");
        DeviceTokenService.IssuedToken b = service.issue(42L, "办公室台式机");

        DeviceTokenService.ResolvedToken ra = service.resolve(a.plaintext());
        DeviceTokenService.ResolvedToken rb = service.resolve(b.plaintext());

        assertEquals(42L, ra.userId());
        assertEquals(42L, rb.userId());
        assertEquals(a.id(), ra.tokenId());
        assertEquals(b.id(), rb.tokenId());
        assertNotEquals(ra.tokenId(), rb.tokenId(), "同一个人的两台机器必须分得开");
    }

    @Test
    @DisplayName("resolveUserId 仍是原语义：委托 resolve，未命中给 null")
    void resolveUserIdStillDelegates() {
        DeviceTokenService.IssuedToken issued = service.issue(7L, "本机");
        assertEquals(7L, service.resolveUserId(issued.plaintext()));

        assertNull(service.resolve("awdt_nope"));
        assertNull(service.resolveUserId("awdt_nope"));
        assertNull(service.resolve(null));
        assertNull(service.resolve("not-a-device-token"), "前缀不对一律不查库");
    }
}
