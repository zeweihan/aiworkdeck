package com.checkba.controller;

import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.PlatformAiChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 病灶：GET /api/account/usage 的 platformUsage() 只捕获 AccountException。官网
 * /api/account/ledger 的 entries 数组里混进一个非对象元素（契约漂移/网站侧 bug）会在
 * .stream().filter(entry -&gt; ... entry.get("kind") ...) 这一步抛 ClassCastException——
 * 不是 AccountException，接不住，冒泡出 usage()，把已经算好的 local 段一起丢掉。
 * 本该只是「platform 段降级」，结果变成整个端点报错，即使本地统计完全正常。
 */
class AccountControllerUsageMalformedLedgerTest {

    private AccountController controller;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        AuthController.registerLocalIdentityService(null);
        accountService = mock(AccountService.class);
        MachineAccountGuard guard = mock(MachineAccountGuard.class); // requireMachineScope 默认放行（void no-op）
        controller = new AccountController(accountService, mock(PlatformAiChannel.class),
                mock(AccountSwitchCleanup.class), mock(TokenUsageRepository.class), guard,
                mock(com.checkba.service.entitlement.EntitlementService.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("platform ledger 混进非对象元素时，platform 段降级、local 段仍然返回")
    void malformedLedgerEntryDegradesPlatformSectionOnly() {
        when(accountService.isConnected()).thenReturn(true);
        when(accountService.fetchProfile()).thenReturn(Map.of("balanceCents", 100, "plan", "free"));
        // entries 里混进一个非对象元素：静态类型骗过编译器，运行时才在流处理里炸
        List<Map<String, Object>> malformed = (List<Map<String, Object>>) (List<?>) List.of("not-a-map");
        when(accountService.fetchLedger()).thenReturn(malformed);

        Map<String, Object> resp = controller.usage(null);

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertNotNull(data.get("local"), "local 段不该被一起丢掉");
        Map<String, Object> platform = (Map<String, Object>) data.get("platform");
        assertEquals(false, platform.get("available"), "platform 段应该降级而不是让整个端点报错");
    }
}
