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

    /**
     * 外部账户桥接（awdk-login）建的无密码账号的口令哨兵前缀。
     * {@link #login} 见到该前缀直接按凭据错误拒绝——这类账号不存在「正确密码」这回事。
     * 哨兵后面还拼了 32 字节随机料作兜底：即使前缀检查被误删，历史明文兼容分支的
     * equals 比对也没有任何用户输入能命中它。
     */
    public static final String EXTERNAL_ACCOUNT_MARK = "{external-account}";

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

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
            throw new IllegalArgumentException(LangText.of("用户名不能为空", "Username cannot be empty"));
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException(LangText.of("密码不能为空", "Password cannot be empty"));
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException(LangText.of("密码长度不能少于6位", "Password must be at least 6 characters"));
        }

        // 检查用户名是否已存在
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException(LangText.of("用户名已存在", "Username already exists"));
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
     * 外部账户桥接建号（awdk-login 首登）：无密码账户，只能经桥接换取设备令牌，
     * 不可用密码登录（见 {@link #EXTERNAL_ACCOUNT_MARK}）。
     */
    public User registerExternal(String username, String displayName) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException(LangText.of("用户名不能为空", "Username cannot be empty"));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException(LangText.of("用户名已存在", "Username already exists"));
        }
        byte[] raw = new byte[32];
        SECURE_RANDOM.nextBytes(raw);
        User user = new User();
        user.setUsername(username);
        user.setPassword(EXTERNAL_ACCOUNT_MARK
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
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
            throw new IllegalArgumentException(LangText.of("用户名不能为空", "Username cannot be empty"));
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException(LangText.of("密码不能为空", "Password cannot be empty"));
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("用户名或密码错误", "Incorrect username or password"));
        }

        User user = userOpt.get();
        String stored = user.getPassword();
        // 外部账户桥接建的无密码账号：一律按凭据错误拒绝（文案与普通失败一致，不泄露账号类型）
        if (stored != null && stored.startsWith(EXTERNAL_ACCOUNT_MARK)) {
            throw new IllegalArgumentException(LangText.of("用户名或密码错误", "Incorrect username or password"));
        }
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
            throw new IllegalArgumentException(LangText.of("用户名或密码错误", "Incorrect username or password"));
        }

        return user;
    }

    /**
     * 根据 ID 获取用户
     */
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("用户不存在: ", "User does not exist: ") + id));
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

