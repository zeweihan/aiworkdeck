// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「这个外部服务此刻能不能用」的判据（dev-board#750）。
 *
 * <p>方向不对称，这是本类最要紧的一点：判成<b>不可用</b>会让工具从模型眼前消失，
 * 表现是「这个能力整个不存在」；判成可用最多白花一轮。所以只有一种情况返回 false。
 */
class ExternalServiceAvailabilityTest {

    private ExternalServiceAvailability availability(ExternalServiceProvider provider, boolean connected) {
        ExternalProviderResolver resolver = mock(ExternalProviderResolver.class);
        when(resolver.resolve(anyString())).thenReturn(provider);
        PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
        when(gateway.connected()).thenReturn(connected);
        return new ExternalServiceAvailability(resolver, gateway);
    }

    @Test
    @DisplayName("平台档 + 没连账户 = 不可用（唯一返回 false 的情况）")
    void platformWithoutAccountIsUnusable() {
        assertFalse(availability(ExternalServiceProvider.PLATFORM, false)
                .usable(ExternalServiceProvider.SEARCH));
    }

    @Test
    @DisplayName("平台档 + 已连账户 = 可用")
    void platformWithAccountIsUsable() {
        assertTrue(availability(ExternalServiceProvider.PLATFORM, true)
                .usable(ExternalServiceProvider.SEARCH));
    }

    @Test
    @DisplayName("BYOK / LOCAL 档一律当可用——凭证判据分散在各 service 里，在这里猜必然出错")
    void byokAndLocalAreAlwaysTreatedAsUsable() {
        assertTrue(availability(ExternalServiceProvider.BYOK, false)
                .usable(ExternalServiceProvider.PKULAW));
        assertTrue(availability(ExternalServiceProvider.LOCAL, false)
                .usable(ExternalServiceProvider.QICHACHA));
    }

    @Test
    @DisplayName("判据本身抛异常时倒向可用：藏掉一个能用的工具比失败一次严重得多")
    void failureToJudgeFallsBackToUsable() {
        ExternalProviderResolver resolver = mock(ExternalProviderResolver.class);
        when(resolver.resolve(anyString())).thenThrow(new IllegalStateException("db down"));
        ExternalServiceAvailability a =
                new ExternalServiceAvailability(resolver, mock(PlatformGatewayClient.class));
        assertTrue(a.usable(ExternalServiceProvider.TUSHARE));
    }
}
