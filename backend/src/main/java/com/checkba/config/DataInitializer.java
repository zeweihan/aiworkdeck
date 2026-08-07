package com.checkba.config;

import com.checkba.controller.WizardController;
import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据初始化器
 * 在应用启动时创建默认用户 admin（社区版首启默认账号，生产请及时改密）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SystemSettingService systemSettingService;

    /**
     * 首启 admin 初始口令。桌面 profile 在 application-desktop.yml 里显式设为 "123"
     * （单机本地、且全员皆管理员，弱口令不构成额外风险，向导页也是这么写的）。
     * 云端/团队部署不设此项：此时随机生成一次性强口令并打印到启动日志，
     * 由部署者取用后立刻改密——服务器上绝不能出现众所周知的默认口令。
     */
    @org.springframework.beans.factory.annotation.Value("${security.admin.initial-password:}")
    private String initialPassword;

    /**
     * 单机免登模式：admin 用户只是本机用户的数据宿主（LocalIdentityService 复用其 userId），
     * 登录入口已不存在，「默认密码请修改」类提示只会造成困惑，故不再打印。
     */
    @org.springframework.beans.factory.annotation.Value("${security.local-mode:false}")
    private boolean localMode;

    @Override
    public void run(String... args) {
        java.util.Optional<User> existingAdmin = userRepository.findByUsername("admin");
        boolean freshInstall = existingAdmin.isEmpty();
        // 创建默认用户 admin（若不存在）。不再打印明文口令到日志。
        existingAdmin
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername("admin");
                    boolean generated = initialPassword == null || initialPassword.isBlank();
                    String password = generated ? generateInitialPassword() : initialPassword;
                    user.setPassword(com.checkba.service.UserService.encodePassword(password));
                    user.setDisplayName("管理员");
                    user.setCreatedAt(LocalDateTime.now());
                    user.setUpdatedAt(LocalDateTime.now());
                    if (localMode) {
                        // 单机免登模式：不打口令类提示（登录已不存在）
                        log.info("已创建默认用户 admin（单机模式，作为本机用户的数据宿主）");
                    } else if (generated) {
                        log.warn("已创建默认用户 admin，随机初始口令为：{}", password);
                        log.warn("该口令仅在本次启动日志中出现一次，请立即登录修改，并清理日志。");
                    } else {
                        log.info("已创建默认用户 admin（请尽快修改默认密码）");
                    }
                    return userRepository.save(user);
                });
        // 注意：此前每次启动会把所有项目强行归到 admin 名下——在多用户场景会"抢走"其他用户的项目，
        // 与 ProjectMemberService 等多用户能力冲突，已移除该逻辑。

        // 全新安装：在任何人往 system_setting 写第一行之前，先把向导标记钉成 "false"。
        // 不钉的话 WizardController.isInitialized() 会走"标记不存在 → 表非空即已初始化"
        // 的存量兜底，而首启链上 LocalIdentityService 解析本机身份时就会写下
        // local.identity.selectedUserId 这一行（launch 页查身份在查向导之前），
        // 结果是全新安装反而跳过首启向导：用户没选过 AI 提供商，要到发第一条消息才发现。
        if (freshInstall && systemSettingService.get(WizardController.KEY_WIZARD_COMPLETED, null) == null) {
            systemSettingService.set(WizardController.KEY_WIZARD_COMPLETED, "false");
            log.info("全新安装：已标记首启向导待运行");
        }
    }

    private static String generateInitialPassword() {
        byte[] bytes = new byte[18];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}


