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
     * 按手机号取账号，没有就建一个（手机号免密登录的注册与登录合一）。
     *
     * 三条约束：
     * - **一号一账号**（维护者 2026-08-17 定）：靠 findByPhone 唯一命中保证，
     *   DB 侧另有唯一约束兜底。
     * - 用户名不用手机号：username 会在各处展示，用手机号等于到处泄露联系方式。
     *   用随机短串，展示名用脱敏号。
     * - 账号无密码（走 registerExternal 的外部账号形态），只能靠验证码进。
     *
     * @return 账号与「是否本次新建」
     */
    @org.springframework.transaction.annotation.Transactional
    public PhoneAccount findOrCreateByPhone(String phone) {
        java.util.Optional<User> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            return new PhoneAccount(existing.get(), false);
        }
        String username = allocatePhoneUsername();
        User user = registerExternal(username, com.checkba.service.sms.SmsAuthService.maskPhone(phone));
        user.setPhone(phone);
        user.setUpdatedAt(LocalDateTime.now());
        return new PhoneAccount(userRepository.save(user), true);
    }

    public record PhoneAccount(User user, boolean created) {}

    /** 随机短用户名，撞了重试。10 次都撞说明随机源坏了，宁可报错也不静默降级。 */
    private String allocatePhoneUsername() {
        for (int i = 0; i < 10; i++) {
            byte[] raw = new byte[6];
            SECURE_RANDOM.nextBytes(raw);
            String candidate = "u" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw).replaceAll("[^A-Za-z0-9]", "").toLowerCase();
            if (userRepository.findByUsername(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法分配用户名");
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

