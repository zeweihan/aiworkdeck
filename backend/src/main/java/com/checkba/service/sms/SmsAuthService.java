package com.checkba.service.sms;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 登录短信验证的流程编排：手机号绑定、登录二次验证的发码与核销。
 *
 * <p>仅 server 模式生效——local-mode 是免登单机形态，没有登录环节，
 * {@link #active()} 恒 false，所有端点自然退化为未启用。
 *
 * <p>场景常量即 {@link SmsCodeStore} 的 scene 维度：login 码只能用于登录、
 * bind 码只能用于绑定，互不通用。
 */
@Service
public class SmsAuthService {

    static final String SCENE_LOGIN = "login";
    static final String SCENE_BIND = "bind";

    /** 大陆手机号；短信通道是国内签名+模板，境外号本就发不了。 */
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final SmsService smsService;
    private final SmsCodeStore codeStore;
    private final UserRepository userRepository;
    private final boolean localMode;

    @Autowired
    public SmsAuthService(SmsService smsService, SmsCodeStore codeStore, UserRepository userRepository,
                          @Value("${security.local-mode:false}") boolean localMode) {
        this.smsService = smsService;
        this.codeStore = codeStore;
        this.userRepository = userRepository;
        this.localMode = localMode;
    }

    /** 短信验证在本部署形态下是否启用。 */
    public boolean active() {
        return !localMode && smsService.enabled();
    }

    /** 该用户本次登录是否需要短信验证码（启用且已绑定手机号；未绑定的存量用户不拦）。 */
    public boolean requiresCode(User user) {
        return active() && user != null && StringUtils.hasText(user.getPhone());
    }

    /** 给已绑定手机号的用户发登录验证码，返回脱敏手机号。调用方须先完成密码校验。 */
    public String sendLoginCode(User user) {
        if (!requiresCode(user)) {
            throw new IllegalArgumentException("短信验证未启用");
        }
        sendWithRollback(SCENE_LOGIN, user.getPhone());
        return maskPhone(user.getPhone());
    }

    /** 核销登录验证码；错码/过期抛业务错误（在线爆破由 SmsCodeStore 的尝试上限兜底）。 */
    public void verifyLoginCode(User user, String code) {
        if (!requiresCode(user)) {
            return;
        }
        if (!codeStore.verify(SCENE_LOGIN, user.getPhone(), code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
    }

    /** 给待绑定的手机号发验证码（需已通过会话鉴权），返回脱敏手机号。 */
    public String sendBindCode(Long userId, String phone) {
        requireActive();
        String normalized = normalizePhone(phone);
        requireNotBoundByOther(userId, normalized);
        sendWithRollback(SCENE_BIND, normalized);
        return maskPhone(normalized);
    }

    /** 校验验证码并完成绑定（也用于更换手机号：直接绑新号覆盖）。 */
    public String confirmBind(Long userId, String phone, String code) {
        requireActive();
        String normalized = normalizePhone(phone);
        requireNotBoundByOther(userId, normalized);
        if (!codeStore.verify(SCENE_BIND, normalized, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        user.setPhone(normalized);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return maskPhone(normalized);
    }

    /** 138****0070；空号回空串（Map.of 不收 null）。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) return "";
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private void sendWithRollback(String scene, String phone) {
        String code = codeStore.issue(scene, phone);
        try {
            smsService.sendVerificationCode(phone, code);
        } catch (RuntimeException e) {
            // 没发出去就不该占用户的冷却期（当日配额不回滚：网关受理即可能计费）
            codeStore.invalidate(scene, phone);
            throw e;
        }
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalArgumentException("短信验证未启用");
        }
    }

    private static String normalizePhone(String phone) {
        String trimmed = phone == null ? "" : phone.replaceAll("\\s", "");
        if (!MAINLAND_PHONE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        return trimmed;
    }

    private void requireNotBoundByOther(Long userId, String phone) {
        Optional<User> holder = userRepository.findByPhone(phone);
        if (holder.isPresent() && !holder.get().getId().equals(userId)) {
            throw new IllegalArgumentException("该手机号已绑定其他账号");
        }
    }
}
