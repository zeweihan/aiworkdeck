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
}
