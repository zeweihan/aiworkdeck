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
    /**
     * 手机号转移后要作废原持有者的会话。用 @Lazy 打断 UserSessionService ->
     * AuthController.registerUserSessionService -> ... 这条启动期的相互引用。
     */
    @org.springframework.context.annotation.Lazy
    private final UserSessionService userSessionService;

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

    /**
     * App 审核账号：按已验证邮箱找，没有就建一个**专用空账号**。
     *
     * <p>只给 {@link com.checkba.config.ReviewAccountGate} 那条路调用。
     *
     * <p>为什么要建号而不是要求事先绑好：{@code verified_email} 全仓只有
     * {@code MailAuthService.confirmBind} 一处写入，而它要求先登录——也就是说
     * 「事先绑好」等于把审核邮箱绑到某个真人账号上，那个固定验证码就成了进
     * 真人账号的钥匙。单独建一个空账号，那把码就只开得了这一个空房间。
     *
     * <p>手机号那条不需要这个方法：{@link #findOrCreateByPhone} 本来就建号。
     */
    @org.springframework.transaction.annotation.Transactional
    public User findOrCreateReviewAccount(String verifiedEmail) {
        return userRepository.findByVerifiedEmail(verifiedEmail).orElseGet(() -> {
            User user = registerExternal(allocatePhoneUsername(), "App Review");
            user.setEmail(verifiedEmail);
            user.setVerifiedEmail(verifiedEmail);
            user.setUpdatedAt(LocalDateTime.now());
            return userRepository.save(user);
        });
    }

    public record PhoneAccount(User user, boolean created) {}

    private static final org.slf4j.Logger claimLog = org.slf4j.LoggerFactory.getLogger(UserService.class);

    /**
     * 桥接认领手机号（手机端账号归一，dev-board#30）：官网账户带着经短信验证的手机号
     * 来桥接时，把该号写到桥接用户名下——此后手机端 sms-login 的
     * {@link #findOrCreateByPhone} 自然解析到同一个账号，不再另建孤号。
     *
     * <p>号码正被别的用户占用时<b>转移</b>：占用方是经 sms-login 对同一手机号
     * 验证过控制权的账号，与官网账户持有人是同一个人，归一正是本方法的目的。
     * 桥接用户已绑了<b>另一个</b>号码时不覆盖（只记日志）——覆盖会悄悄改变
     * 一个已工作的登录入口。
     *
     * <p>永不抛出：认领是桥接的顺手动作，失败不影响桥接本身。
     */
    @org.springframework.transaction.annotation.Transactional
    public void claimPhoneFromWebsite(User user, String phone) {
        try {
            if (user == null || phone == null || !phone.matches("^1\\d{10}$")) return;
            if (phone.equals(user.getPhone())) return;
            if (StringUtils.hasText(user.getPhone())) {
                claimLog.info("桥接用户 {} 已绑 {}，不用官网号码覆盖",
                        user.getId(), com.checkba.service.sms.SmsAuthService.maskPhone(user.getPhone()));
                return;
            }
            Optional<User> holder = userRepository.findByPhone(phone);
            if (holder.isPresent() && !holder.get().getId().equals(user.getId())) {
                User h = holder.get();
                h.setPhone(null);
                h.setUpdatedAt(LocalDateTime.now());
                userRepository.save(h);
                claimLog.info("手机号 {} 从用户 {} 转移到桥接用户 {}（账号归一）",
                        com.checkba.service.sms.SmsAuthService.maskPhone(phone), h.getId(), user.getId());
                // 转移完必须把原持有者的会话一并作废。否则手机端手上那张老会话仍然有效，
                // 它会继续以老账号的身份请求 /api/mobile/projects——而目录镜像挂在归一后的
                // 账号名下，老账号名下空空如也，返回的是合法的空数组：**没有报错、没有提示，
                // 用户看到的就是「一个项目都读不到」，而且怎么重进都一样**（会话不过期就永远不会自愈）。
                // 作废后手机端被迫重新走短信登录，落到归一后的账号上。
                // 同一个人：占用方本就是用这个号码验证过控制权的账号。
                try {
                    userSessionService.revokeAllForUser(h.getId());
                } catch (Exception e) {
                    claimLog.warn("作废原持有者 {} 的会话失败（手机号已转移，用户需手动重新登录）", h.getId(), e);
                }
            }
            user.setPhone(phone);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        } catch (Exception e) {
            claimLog.warn("桥接认领手机号失败（不影响桥接）: user={}", user == null ? null : user.getId(), e);
        }
    }

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

