package com.checkba.service;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** BCrypt 无状态、线程安全，可静态复用。 */
    private static final BCryptPasswordEncoder PW_ENCODER = new BCryptPasswordEncoder();

    /** 判断存储的口令是否已是 BCrypt 哈希（$2a/$2b/$2y 前缀）。 */
    private static boolean isBcryptHash(String stored) {
        return stored != null && stored.startsWith("$2");
    }

    /** 供 DataInitializer 等复用的 BCrypt 加密入口。 */
    public static String encodePassword(String raw) {
        return PW_ENCODER.encode(raw);
    }

    /**
     * 用户注册
     */
    public User register(String username, String password, String displayName) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("密码长度不能少于6位");
        }

        // 检查用户名是否已存在
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(PW_ENCODER.encode(password));
        user.setDisplayName(StringUtils.hasText(displayName) ? displayName : username);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    /**
     * 用户登录
     */
    public User login(String username, String password) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("密码不能为空");
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        User user = userOpt.get();
        String stored = user.getPassword();
        boolean ok;
        if (isBcryptHash(stored)) {
            ok = PW_ENCODER.matches(password, stored);
        } else {
            // 兼容历史明文口令：比对成功后就地升级为 BCrypt（无需一次性数据迁移）
            ok = password.equals(stored);
            if (ok) {
                user.setPassword(PW_ENCODER.encode(password));
                userRepository.save(user);
            }
        }
        if (!ok) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        return user;
    }

    /**
     * 根据 ID 获取用户
     */
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    /**
     * 根据用户名获取用户
     */
    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * 更新用户头像
     */
    public User updateAvatar(Long userId, String avatarUrl) {
        User user = getUserById(userId);
        user.setAvatarUrl(avatarUrl);
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }
}

