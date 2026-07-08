package com.checkba.config;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
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

    @Override
    public void run(String... args) {
        // 创建默认用户 admin（若不存在）。不再打印明文口令到日志。
        userRepository.findByUsername("admin")
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername("admin");
                    user.setPassword(com.checkba.service.UserService.encodePassword("123")); // 默认密码（BCrypt）
                    user.setDisplayName("管理员");
                    user.setCreatedAt(LocalDateTime.now());
                    user.setUpdatedAt(LocalDateTime.now());
                    log.info("已创建默认用户 admin（请尽快修改默认密码）");
                    return userRepository.save(user);
                });
        // 注意：此前每次启动会把所有项目强行归到 admin 名下——在多用户场景会"抢走"其他用户的项目，
        // 与 ProjectMemberService 等多用户能力冲突，已移除该逻辑。
    }
}


