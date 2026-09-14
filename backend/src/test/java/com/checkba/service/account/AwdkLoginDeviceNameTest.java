// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.DeviceToken;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桥接时带上设备名（spec 2026-09-14 §2.3）：桌面端连官方案件库时传本机主机名，
 * 事件行才说得出「你在另一台电脑（{设备名}）交了稿」。
 *
 * <p>回包契约一字不动：{@code BridgeSession} 的五个分量原样，缺 tokenId 时上层
 * 整键不下发那条仍由 {@code AuthControllerHardeningTest.awdkLoginOmitsTokenIdWhenAbsent} 守着。
 */
class AwdkLoginDeviceNameTest {

    private static final String KEY = "awdk_" + "BridgeKeyMaterial0123456789abcdefghijkl";
    private static final String ME_OK =
            "{\"accountId\":\"acc_9f3a\",\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\"}";

    static class StubTransport implements AccountTransport {
        final List<String> calls = new ArrayList<>();
        @Override
        public Reply send(String method, String url, String bearerKey, String jsonBody) {
            calls.add(method + " " + url);
            return new Reply(200, ME_OK);
        }
    }

    private final Map<String, User> usersByName = new HashMap<>();
    private final Map<Long, User> usersById = new HashMap<>();
    private final Map<String, AccountBinding> bindings = new HashMap<>();
    private final Map<String, DeviceToken> tokensByHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    private AwdkLoginService service;
    private DeviceTokenService deviceTokenService;

    @BeforeEach
    void setUp() {
        usersByName.clear();
        usersById.clear();
        bindings.clear();
        tokensByHash.clear();

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(usersByName.get(inv.getArgument(0, String.class))));
        when(userRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(usersById.get(inv.getArgument(0, Long.class))));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(seq.getAndIncrement());
            usersByName.put(u.getUsername(), u);
            usersById.put(u.getId(), u);
            return u;
        });
        UserService userService = new UserService(userRepository,
                mock(com.checkba.service.UserSessionService.class));

        DeviceTokenRepository tokenRepository = mock(DeviceTokenRepository.class);
        when(tokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            tokensByHash.put(t.getTokenHash(), t);
            return t;
        });
        when(tokenRepository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tokensByHash.get(inv.getArgument(0, String.class))));
        deviceTokenService = new DeviceTokenService(tokenRepository);

        AccountBindingRepository bindingRepository = mock(AccountBindingRepository.class);
        when(bindingRepository.findByExternalAccountId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(bindings.get(inv.getArgument(0, String.class))));
        when(bindingRepository.save(any(AccountBinding.class))).thenAnswer(inv -> {
            AccountBinding b = inv.getArgument(0);
            if (b.getId() == null) b.setId(seq.getAndIncrement());
            bindings.put(b.getExternalAccountId(), b);
            return b;
        });

        service = new AwdkLoginService(true, "https://www.aiworkdeck.com", new StubTransport(),
                bindingRepository, userService, deviceTokenService,
                mock(com.checkba.service.ai.PlatformAiKeyService.class));
    }

    private String nameOfIssuedToken(AwdkLoginService.BridgeSession session) {
        return tokensByHash.values().stream()
                .filter(t -> t.getId().equals(session.tokenId()))
                .map(DeviceToken::getName)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("带 deviceName：设备名落在令牌上，事件行据此说出是哪一台电脑")
    void deviceNameLandsOnTheToken() {
        AwdkLoginService.BridgeSession session = service.login(KEY, "韩泽伟的 MacBook Pro");

        assertEquals("韩泽伟的 MacBook Pro", nameOfIssuedToken(session));
        // 回包五个分量一个不少（桌面端要靠 tokenId 撤令牌、靠 userId 判「这条是我干的」）
        assertNotNull(session.token());
        assertNotNull(session.userId());
        assertEquals("韩泽伟", session.displayName());
        assertNotNull(session.tokenId());
        assertNotNull(session.username());
        // 同一枚令牌解析回同一个人与同一台设备
        DeviceTokenService.ResolvedToken who = deviceTokenService.resolve(session.token());
        assertEquals(session.userId(), who.userId());
        assertEquals(session.tokenId(), who.tokenId());
    }

    @Test
    @DisplayName("不带 deviceName（老客户端、取不到主机名）：仍是「账户桥接」，行为一字不变")
    void missingDeviceNameKeepsTheOldLabel() {
        assertEquals("账户桥接", nameOfIssuedToken(service.login(KEY)));
        assertEquals("账户桥接", nameOfIssuedToken(service.login(KEY, null)));
        assertEquals("账户桥接", nameOfIssuedToken(service.login(KEY, "   ")));
    }
}
