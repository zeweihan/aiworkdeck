package com.checkba.service;

import com.checkba.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 护栏：向导初始化判据与**匿名窗口边界**。
 *
 * <p>后者是安全前置条件——向导 POST 与向导里的辅助端点（本地 Ollama 探测）
 * 共用它决定要不要放行无会话请求。最关键的一条是
 * {@link #resetWindowIsNotAnonymous()}：管理员 reset 打开的窗口里
 * {@code isInitialized()} 同样是 false，但它**必须**要求管理员会话，
 * 否则任何能连到本机的人都能在窗口期改写 AI baseUrl 与系统提示词。
 */
@ExtendWith(MockitoExtension.class)
class WizardStateServiceTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private SystemSettingRepository systemSettingRepository;

    private WizardStateService service() {
        return new WizardStateService(systemSettingService, systemSettingRepository);
    }

    @Test
    void freshInstallIsAnonymousWindow() {
        when(systemSettingService.get(WizardStateService.KEY_WIZARD_COMPLETED, null)).thenReturn(null);
        when(systemSettingRepository.count()).thenReturn(0L);

        WizardStateService s = service();
        assertFalse(s.isInitialized(), "全新安装不算已初始化");
        assertTrue(s.inAnonymousSetupWindow(), "全新安装是匿名向导窗口");
    }

    @Test
    void resetWindowIsNotAnonymous() {
        // 管理员 reset：标记显式为 "false"，向导重新开放，但不是匿名窗口
        when(systemSettingService.get(WizardStateService.KEY_WIZARD_COMPLETED, null)).thenReturn("false");
        lenient().when(systemSettingRepository.count()).thenReturn(0L);

        WizardStateService s = service();
        assertFalse(s.isInitialized(), "reset 后向导重新开放");
        assertFalse(s.inAnonymousSetupWindow(),
                "reset 窗口必须带管理员会话——匿名放行等于把改写 AI baseUrl 的入口重新打开");
    }

    @Test
    void completedInstallIsInitializedAndClosed() {
        when(systemSettingService.get(WizardStateService.KEY_WIZARD_COMPLETED, null)).thenReturn("true");
        lenient().when(systemSettingRepository.count()).thenReturn(12L);

        WizardStateService s = service();
        assertTrue(s.isInitialized());
        assertFalse(s.inAnonymousSetupWindow());
    }

    @Test
    void legacyInstallWithoutMarkerFallsBackToSettingsCount() {
        // 存量部署：标记从未写过，但库里有配置 —— 按已初始化处理，防匿名滥用
        when(systemSettingService.get(WizardStateService.KEY_WIZARD_COMPLETED, null)).thenReturn(null);
        when(systemSettingRepository.count()).thenReturn(7L);

        WizardStateService s = service();
        assertTrue(s.isInitialized(), "存量库非空即视为已初始化");
        assertFalse(s.inAnonymousSetupWindow(), "存量库非空不是匿名窗口");
    }
}
