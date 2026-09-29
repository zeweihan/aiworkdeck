// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import com.checkba.controller.WizardController;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 锁定「全新安装必须走首启向导」这条不变式。
 *
 * <p>没有这枚显式标记时，{@code WizardController.isInitialized()} 会退回存量兜底
 * 「system_setting 非空即已初始化」，而首启链上 LocalIdentityService 解析本机身份
 * 会先写下一行 selectedUserId——全新安装反而跳过向导，用户没选过 AI 提供商，
 * 要到发第一条消息才发现。
 */
@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SystemSettingService systemSettingService;

    private DataInitializer newInitializer() {
        return new DataInitializer(userRepository, systemSettingService);
    }

    @Test
    void freshInstallPinsWizardPending() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn(null);

        newInitializer().run();

        verify(systemSettingService).set(WizardController.KEY_WIZARD_COMPLETED, "false");
    }

    @Test
    void existingInstallIsNotReopened() {
        // 已有 admin = 存量库：绝不能把向导标记重置成 "false"（等于把匿名提交窗口重新打开）
        User admin = new User();
        admin.setUsername("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        newInitializer().run();

        verify(systemSettingService, never()).set(anyString(), anyString());
    }

    // ==================== 登录后置：首启初始化由后端接管（dev-board#1046） ====================

    private DataInitializer localModeInitializer() {
        DataInitializer init = newInitializer();
        org.springframework.test.util.ReflectionTestUtils.setField(init, "localMode", true);
        return init;
    }

    @Test
    void localModeFreshInstallDefaultsToPlatformChannel() {
        // 原来由解锁页 completeSetup() 提交向导完成；登录后置之后不再有「登录成功」这个时点，
        // 后端在启动期把供应商定成官方通道，并把向导标记收口（匿名向导窗口不留着）
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn(null, "false");
        when(systemSettingService.get("ai.activeProvider", null)).thenReturn(null);

        localModeInitializer().run();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(systemSettingService).setMany(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("AWD_CLOUD", captor.getValue().get("ai.activeProvider"));
        org.junit.jupiter.api.Assertions.assertEquals("true", captor.getValue().get(WizardController.KEY_WIZARD_COMPLETED));
    }

    @Test
    void localModeExistingInstallWithoutProviderAlsoGetsPlatformChannel() {
        // 存量机器：旧版上从没过解锁门（因而从没提交过向导）的装机，同样补上
        User admin = new User();
        admin.setUsername("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn("false");
        when(systemSettingService.get("ai.activeProvider", null)).thenReturn("  ");

        localModeInitializer().run();

        verify(systemSettingService).setMany(argThat(m -> "AWD_CLOUD".equals(m.get("ai.activeProvider"))));
    }

    @Test
    void localModeNeverOverwritesAChosenProvider() {
        // 用户选过（哪怕选的是本地 Ollama）就一个字都不动
        User admin = new User();
        admin.setUsername("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(systemSettingService.get("ai.activeProvider", null)).thenReturn("OLLAMA");

        localModeInitializer().run();

        verify(systemSettingService, never()).setMany(any());
        verify(systemSettingService, never()).set(anyString(), anyString());
    }

    @Test
    void localModeRespectsACompletedWizardEvenWithoutProvider() {
        // 向导已完成却没有供应商这一行：那是管理员的显式状态，不替他改
        User admin = new User();
        admin.setUsername("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(systemSettingService.get("ai.activeProvider", null)).thenReturn(null);
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn("true");

        localModeInitializer().run();

        verify(systemSettingService, never()).setMany(any());
    }

    @Test
    void serverModeFreshInstallDoesNotPickAProvider() {
        // 团队服务器：供应商由管理员在向导里选，后端不替他定
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null)).thenReturn(null);

        newInitializer().run();

        verify(systemSettingService, never()).setMany(any());
    }
}
