package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.repository.SystemSettingRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * 锁定向导重置语义（存量安装换 Key 的入口）：
 * - completed 标记显式存在时以它为准（reset 后 "false" → 向导重新开放，
 *   即使 system_setting 非空）；
 * - 标记不存在的存量部署仍按"保存过任何配置即已初始化"兜底（防匿名滥用）；
 * - /reset 仅 admin 会话可调；
 * - 向导提交只在全新安装时匿名放行，reset 重开的窗口需管理员会话。
 */
@ExtendWith(MockitoExtension.class)
class WizardControllerTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private SystemSettingRepository systemSettingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private com.checkba.service.ai.ChatModelFactory chatModelFactory;

    private WizardController newController() {
        return new WizardController(systemSettingService, systemSettingRepository,
                userRepository, adminAccessService, new ObjectMapper(), chatModelFactory);
    }

    @Test
    void statusReopensAfterReset_evenWithExistingSettings() {
        // reset 后：标记显式为 "false"，settings 表非空也不再一票否决
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn("false");

        ResponseEntity<?> resp = newController().status();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("initialized"));
        // 显式标记生效时不应再查兜底 count
        verify(systemSettingRepository, never()).count();
    }

    @Test
    void statusLegacyDeploymentWithoutFlagStaysInitialized() {
        // 存量部署：无标记但保存过配置 → 视为已初始化（防向导端点匿名滥用）
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn(null);
        when(systemSettingRepository.count()).thenReturn(5L);

        ResponseEntity<?> resp = newController().status();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("initialized"));
    }

    private static AdminConfigController.AdminConfigUpdateRequest providerRequest() {
        AdminConfigController.AiConfig ai = new AdminConfigController.AiConfig();
        ai.setActiveProvider("OPENROUTER");
        AdminConfigController.AdminConfigUpdateRequest req =
                new AdminConfigController.AdminConfigUpdateRequest();
        req.setAi(ai);
        return req;
    }

    @Test
    void initializeAllowsAnonymousOnFreshInstall() {
        // 全新安装：标记从未写过且无任何配置 → 向导本职，匿名放行一次
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn(null);
        when(systemSettingRepository.count()).thenReturn(0L);

        ResponseEntity<?> resp = newController().initialize(null, providerRequest());
        assertEquals(200, resp.getStatusCode().value());
        verify(systemSettingService).setMany(
                argThat((Map<String, String> m) ->
                        "true".equals(m.get(WizardController.KEY_WIZARD_COMPLETED))));
    }

    @Test
    void initializeAfterResetRequiresAdminSession() {
        // reset 打开的窗口不能是匿名写入口：否则任何人都能改写 AI baseUrl / 系统提示词
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn("false");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            ResponseEntity<?> resp = newController().initialize(null, providerRequest());
            assertEquals(403, resp.getStatusCode().value());
            verify(systemSettingService, never()).setMany(anyMap());
        }
    }

    @Test
    void initializeAfterResetAcceptsAdminSession() {
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn("false");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess-admin")).thenReturn(1L);
            User admin = new User();
            admin.setUsername("admin");
            when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
            when(adminAccessService.isAdmin(admin)).thenReturn(true);

            ResponseEntity<?> resp = newController().initialize("sess-admin", providerRequest());
            assertEquals(200, resp.getStatusCode().value());
        }
    }

    @Test
    void resetRequiresAdminSession() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess-user")).thenReturn(2L);
            User normal = new User();
            normal.setUsername("alice");
            when(userRepository.findById(2L)).thenReturn(Optional.of(normal));
            when(adminAccessService.isAdmin(normal)).thenReturn(false);

            ResponseEntity<?> resp = newController().reset("sess-user");
            assertEquals(403, resp.getStatusCode().value());
            verify(systemSettingService, never()).setMany(anyMap());
        }
    }

    @Test
    void resetByAdminMarksWizardNotCompleted() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess-admin")).thenReturn(1L);
            User admin = new User();
            admin.setUsername("admin");
            when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
            when(adminAccessService.isAdmin(admin)).thenReturn(true);

            ResponseEntity<?> resp = newController().reset("sess-admin");
            assertEquals(200, resp.getStatusCode().value());
            verify(systemSettingService).setMany(
                    argThat((Map<String, String> m) ->
                            "false".equals(m.get(WizardController.KEY_WIZARD_COMPLETED))));
        }
    }
}
