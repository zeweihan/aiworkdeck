package com.checkba.service.sms;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.auth.VerificationCodeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 登录短信验证的流程编排：手机号绑定、登录二次验证的发码与核销。
 *
 * <p>仅 server 模式生效——local-mode 是免登单机形态，没有登录环节，
 * {@link #active()} 恒 false，所有端点自然退化为未启用。
 *
 * <p>场景常量即 {@link VerificationCodeStore} 的 scene 维度：login 码只能用于登录、
 * bind 码只能用于绑定，互不通用。
 */
@Service
public class SmsAuthService {

    static final String SCENE_LOGIN = "login";
    static final String SCENE_BIND = "bind";
    /**
     * 手机号免密登录/注册。与 SCENE_LOGIN 分开：那个是「已知账号的第二因素」，
     * 这个是「拿手机号直接进来」，两者的码不能互相核销。
     */
    static final String SCENE_SIGNIN = "signin";

    /** 大陆手机号（存储为 11 位裸号，历史形态）。 */
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    /** E.164：+ 加国家码，总长 7-15 位。 */
    private static final Pattern E164_PHONE = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private final List<SmsGateway> gateways;
    private final VerificationCodeStore codeStore;
    private final UserRepository userRepository;
    private final boolean localMode;

    @Autowired
    public SmsAuthService(List<SmsGateway> gateways, VerificationCodeStore codeStore, UserRepository userRepository,
                          @Value("${security.local-mode:false}") boolean localMode) {
        this.gateways = gateways;
        this.codeStore = codeStore;
        this.userRepository = userRepository;
        this.localMode = localMode;
    }

    /** 短信验证在本部署形态下是否启用（任一通道可用即算启用）。 */
    public boolean active() {
        return !localMode && gateways.stream().anyMatch(SmsGateway::enabled);
    }

    /** 找负责该号码且已配置的通道；没有则说明这个号段本部署发不了。 */
    private SmsGateway gatewayFor(String phone) {
        return gateways.stream()
                .filter(SmsGateway::enabled)
                .filter(g -> g.supports(phone))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("该号码所在地区暂不支持短信验证", "SMS verification is not yet supported in this number's region")));
    }

    /** 该用户本次登录是否需要短信验证码（启用且已绑定手机号；未绑定的存量用户不拦）。 */
    public boolean requiresCode(User user) {
        return active() && user != null && StringUtils.hasText(user.getPhone());
    }

    /** 给已绑定手机号的用户发登录验证码，返回脱敏手机号。调用方须先完成密码校验。 */
    public String sendLoginCode(User user) {
        if (!requiresCode(user)) {
            throw new IllegalArgumentException(LangText.of("短信验证未启用", "SMS verification is not enabled"));
        }
        sendWithRollback(SCENE_LOGIN, user.getPhone());
        return maskPhone(user.getPhone());
    }

    /** 核销登录验证码；错码/过期抛业务错误（在线爆破由 VerificationCodeStore 的尝试上限兜底）。 */
    public void verifyLoginCode(User user, String code) {
        if (!requiresCode(user)) {
            return;
        }
        if (!codeStore.verify(SCENE_LOGIN, user.getPhone(), code)) {
            throw new IllegalArgumentException(LangText.of("验证码错误或已过期", "Incorrect or expired verification code"));
        }
    }

    /**
     * 手机号免密登录/注册：发验证码。
     *
     * 与邮箱免密登录不同，这里**对未注册的号码也发**——注册与登录合一，
     * 验证通过后没有账号就建一个。所以不存在「该号是否注册过」的枚举面，
     * 代价是任何号码都能触发一条短信，靠 VerificationCodeStore 的冷却期与
     * 当日配额兜住（gatewayFor 先探通道，发不出去不占冷却）。
     */
    public void sendSigninCode(String phone) {
        requireActive();
        sendWithRollback(SCENE_SIGNIN, normalizePhone(phone));
    }

    /**
     * 核销免密登录码，返回规范化后的手机号。
     * 错码与过期一律同一句话，不区分——区分了就是在告诉攻击者哪一步猜对了。
     */
    public String verifySigninCode(String phone, String code) {
        requireActive();
        String normalized = normalizePhone(phone);
        if (!codeStore.verify(SCENE_SIGNIN, normalized, code)) {
            throw new IllegalArgumentException(
                    LangText.of("验证码错误或已过期", "Incorrect or expired verification code"));
        }
        return normalized;
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
            throw new IllegalArgumentException(LangText.of("验证码错误或已过期", "Incorrect or expired verification code"));
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("用户不存在: ", "User does not exist: ") + userId));
        user.setPhone(normalized);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return maskPhone(normalized);
    }

    /** 138****0070 / +141****2671；太短或空号回空串（Map.of 不收 null）。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private void sendWithRollback(String scene, String phone) {
        SmsGateway gateway = gatewayFor(phone); // 先确认通道可用，避免白占一次冷却
        String code = codeStore.issue(scene, phone);
        try {
            gateway.sendVerificationCode(phone, code);
        } catch (RuntimeException e) {
            // 没发出去就不该占用户的冷却期（当日配额不回滚：网关受理即可能计费）
            codeStore.invalidate(scene, phone);
            throw e;
        }
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalArgumentException(LangText.of("短信验证未启用", "SMS verification is not enabled"));
        }
    }

    /**
     * 规范化到存储形态：大陆号一律 11 位裸号（含 +86 前缀的也剥掉，与历史数据同形，
     * 否则同一个号会因写法不同绕过唯一性检查绑到两个账号上）；境外号存 E.164。
     */
    static String normalizePhone(String phone) {
        String trimmed = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        if (trimmed.startsWith("+86")) {
            trimmed = trimmed.substring(3);
        }
        if (MAINLAND_PHONE.matcher(trimmed).matches() || E164_PHONE.matcher(trimmed).matches()) {
            return trimmed;
        }
        throw new IllegalArgumentException(LangText.of("手机号格式不正确", "Invalid phone number format"));
    }

    private void requireNotBoundByOther(Long userId, String phone) {
        Optional<User> holder = userRepository.findByPhone(phone);
        if (holder.isPresent() && !holder.get().getId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("该手机号已绑定其他账号", "This phone number is already linked to another account"));
        }
    }
}
