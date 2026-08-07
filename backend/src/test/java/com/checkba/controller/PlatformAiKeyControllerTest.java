package com.checkba.controller;

import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.ai.PlatformAiKeyService;
import com.checkba.service.ai.PlatformUsageAccountant;
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
import static org.mockito.Mockito.*;

/**
 * 按用户的平台 AI 额度端点。
 *
 * 与 AccountController 的关键区别：这是<b>会话级</b>的（我自己的额度），
 * 不走 MachineAccountGuard 的 admin 闸——那道闸管的是整台服务器的账户连接。
 */
class PlatformAiKeyControllerTest {

    private static final String AWDK = "awdk_" + "KeyMaterial0123456789abcdefghijklmnop";

    private DeviceTokenService deviceTokenService;
    private PlatformAiKeyService keyService;
    private PlatformUsageAccountant accountant;
    private PlatformAiKeyController controller;

    private final Map<String, DeviceToken> tokensByHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        tokensByHash.clear();

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

        keyService = mock(PlatformAiKeyService.class);
        accountant = mock(PlatformUsageAccountant.class);
        controller = new PlatformAiKeyController(keyService, accountant);
    }

    private String tokenFor(long userId) {
        return deviceTokenService.issue(userId, "test").plaintext();
    }

    @Test
    @DisplayName("普通用户（非 admin）也能查自己的额度：这是会话级状态，不是机器级配置")
    void anyAuthenticatedUserCanReadOwnQuota() {
        when(keyService.status(eq(9L), any())).thenReturn(Map.of("available", true, "limitUsd", 10.0));

        Map<String, Object> result = controller.status(tokenFor(9L));

        assertEquals(0, result.get("code"));
        verify(keyService).status(eq(9L), any());
    }

    @Test
    @DisplayName("无会话：报「未登录」——这是真的掉线，本就该触发前端重新认证")
    void missingSessionIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.status("awdt_not-a-real-token"));
        assertEquals("未登录", e.getMessage());
        verifyNoInteractions(keyService);
    }

    @Test
    @DisplayName("刷新：按会话身份刷新，并作废旧指纹的对账基线")
    void refreshUsesSessionIdentityAndResetsBaseline() {
        when(keyService.status(eq(9L), any())).thenReturn(Map.of("available", true));

        controller.refresh(Map.of("key", AWDK), tokenFor(9L));

        verify(keyService).refresh(9L, AWDK);
        verify(accountant).resetBaseline();
    }

    @Test
    @DisplayName("刷新 body 缺失：交给服务层的格式校验，不 NPE")
    void refreshWithoutBodyIsHandled() {
        String token = tokenFor(9L);
        assertDoesNotThrow(() -> {
            try {
                controller.refresh(null, token);
            } catch (com.checkba.service.account.AccountException expected) {
                // 服务层的格式拒绝是预期路径
            }
        });
        verify(keyService).refresh(9L, null);
    }

    @Test
    @DisplayName("业务错误回全站信封 code=1，且文案不被前端误判为掉线")
    void accountExceptionBecomesBusinessEnvelope() {
        var response = controller.handleAccountException(new com.checkba.service.account.AccountException(
                com.checkba.service.account.AccountException.Kind.CONFLICT,
                "这枚账户 Key 属于另一个 AI Workdeck 账户，与本账号的直连关系不一致"));

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("code"));
        assertEquals("CONFLICT", body.get("kind"));
        String message = String.valueOf(body.get("message"));
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(message.contains(forbidden), "文案不得含「" + forbidden + "」: " + message);
        }
    }
}
