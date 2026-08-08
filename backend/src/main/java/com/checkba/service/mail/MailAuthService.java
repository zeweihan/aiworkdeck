package com.checkba.service.mail;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.auth.VerificationCodeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 邮箱验证码的流程编排：绑定、登录二次验证、免密登录的发码与核销。
 *
 * <p>与 {@code SmsAuthService} 同构，验证码生命周期共用 {@link VerificationCodeStore}
 * （冷却、日上限、爆破上限、一次性只有一份实现）。存在的意义是成本：短信按条计费，
 * 邮件几乎免费，所以绑了邮箱的用户二次验证优先走邮件（判定在 {@code SecondFactorService}）。
 *
 * <p>仅 server 模式生效——local-mode 是免登单机形态，{@link #active()} 恒 false。
 *
 * <h3>三个场景互不通用</h3>
 * bind 码只能用于绑定、login 码只能用于登录二次验证、signin 码只能用于免密登录。
 * 这是 {@link VerificationCodeStore} 的 scene 维度保证的：拿绑定码去免密登录会直接失败。
 * 混用的后果很实际——bind 码是已登录态下发的，若能用于 signin，等于把一个低权限操作
 * 兑换成了完整登录。
 */
@Service
public class MailAuthService {

    static final String SCENE_LOGIN = "mail-login";
    static final String SCENE_BIND = "mail-bind";
    static final String SCENE_SIGNIN = "mail-signin";

    /** 与短信共用一个 store，scene 前缀已带 mail- 以免和手机号场景撞键。 */
    private final VerificationCodeStore codeStore;
    private final MailRouter mailRouter;
    private final UserRepository userRepository;
    private final boolean localMode;
    private final boolean passwordlessEnabled;

    @Autowired
    public MailAuthService(VerificationCodeStore codeStore, MailRouter mailRouter,
                           UserRepository userRepository,
                           @Value("${security.local-mode:false}") boolean localMode,
                           @Value("${mail.passwordless-login-enabled:false}") boolean passwordlessEnabled) {
        this.codeStore = codeStore;
        this.mailRouter = mailRouter;
        this.userRepository = userRepository;
        this.localMode = localMode;
        this.passwordlessEnabled = passwordlessEnabled;
    }

    /** 邮箱验证在本部署形态下是否启用（非 local-mode 且至少一条发信通道配齐）。 */
    public boolean active() {
        return !localMode && mailRouter.active();
    }

    /** 免密登录是否开放。独立开关：它是一条新的匿名登录入口，默认关。 */
    public boolean passwordlessActive() {
        return active() && passwordlessEnabled;
    }

    /** 该用户本次登录是否可以用邮箱做二次验证（启用且已绑定已验证邮箱）。 */
    public boolean requiresCode(User user) {
        return active() && user != null && StringUtils.hasText(user.getVerifiedEmail());
    }

    // ==================== 登录二次验证 ====================

    /** 给已绑定邮箱的用户发登录验证码，返回脱敏地址。调用方须先完成密码校验。 */
    public String sendLoginCode(User user) {
        if (!requiresCode(user)) {
            throw new IllegalArgumentException("邮箱验证未启用");
        }
        sendWithRollback(SCENE_LOGIN, user.getVerifiedEmail(), "登录验证码");
        return maskEmail(user.getVerifiedEmail());
    }

    /** 核销登录验证码；错码/过期抛业务错误（在线爆破由 VerificationCodeStore 的尝试上限兜底）。 */
    public void verifyLoginCode(User user, String code) {
        if (!requiresCode(user)) {
            return;
        }
        if (!codeStore.verify(SCENE_LOGIN, user.getVerifiedEmail(), code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
    }

    // ==================== 绑定 ====================

    /** 给待绑定的邮箱发验证码（需已通过会话鉴权），返回脱敏地址。 */
    public String sendBindCode(Long userId, String email) {
        requireActive();
        String normalized = MailRouter.normalize(email);
        requireNotBoundByOther(userId, normalized);
        sendWithRollback(SCENE_BIND, normalized, "邮箱绑定验证码");
        return maskEmail(normalized);
    }

    /** 校验验证码并完成绑定（也用于更换邮箱：直接绑新地址覆盖）。 */
    public String confirmBind(Long userId, String email, String code) {
        requireActive();
        String normalized = MailRouter.normalize(email);
        requireNotBoundByOther(userId, normalized);
        if (!codeStore.verify(SCENE_BIND, normalized, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        user.setVerifiedEmail(normalized);
        // 资料邮箱为空时顺手补上，已填的不覆盖——那是用户自己写的
        if (!StringUtils.hasText(user.getEmail())) {
            user.setEmail(normalized);
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return maskEmail(normalized);
    }

    // ==================== 免密登录 ====================

    /**
     * 免密登录发码。
     *
     * <p>**邮箱未绑定任何账号时，不发信但照常返回**——回包对「已注册」和「未注册」必须
     * 完全一致，否则这个匿名端点就成了账号枚举器，谁都能拿它批量探测某人是不是用户。
     * IP 维度的限频在 AuthAbuseGuard，邮箱维度的冷却与日上限在 VerificationCodeStore。
     */
    public void sendSigninCode(String email) {
        if (!passwordlessActive()) {
            throw new IllegalArgumentException("邮箱登录未启用");
        }
        String normalized = MailRouter.normalize(email);
        if (userRepository.findByVerifiedEmail(normalized).isEmpty()) {
            return;
        }
        sendWithRollback(SCENE_SIGNIN, normalized, "登录验证码");
    }

    /**
     * 核销免密登录码并返回对应账号。错码/过期/无此账号一律同一句话——
     * 同样是为了不泄露该邮箱是否注册过。
     */
    public User verifySigninCode(String email, String code) {
        if (!passwordlessActive()) {
            throw new IllegalArgumentException("邮箱登录未启用");
        }
        String normalized = MailRouter.normalize(email);
        if (!codeStore.verify(SCENE_SIGNIN, normalized, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        return userRepository.findByVerifiedEmail(normalized)
                .orElseThrow(() -> new IllegalArgumentException("验证码错误或已过期"));
    }

    // ==================== 内部 ====================

    /** h***@gmail.com；太短或空地址回空串（Map.of 不收 null）。 */
    public static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at < 0) return "";
        return (at <= 1 ? "***" : email.charAt(0) + "***") + email.substring(at);
    }

    private void sendWithRollback(String scene, String email, String purpose) {
        // 先确认有通道可用，避免白占一次冷却
        mailRouter.gatewayFor(email);
        String code = codeStore.issue(scene, email);
        try {
            mailRouter.send(email, "AI Workdeck " + purpose,
                    "你的验证码是：" + code + "\n\n"
                            + "5 分钟内有效，请勿转发给任何人。\n"
                            + "如果这不是你本人操作，忽略本邮件即可。\n\n"
                            + "回信不会被系统读取。");
        } catch (RuntimeException e) {
            // 没发出去就不该占用户的冷却期（当日配额不回滚：网关受理即可能计费）
            codeStore.invalidate(scene, email);
            throw e;
        }
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalArgumentException("邮箱验证未启用");
        }
    }

    private void requireNotBoundByOther(Long userId, String email) {
        Optional<User> holder = userRepository.findByVerifiedEmail(email);
        if (holder.isPresent() && !holder.get().getId().equals(userId)) {
            throw new IllegalArgumentException("该邮箱已绑定其他账号");
        }
    }
}
