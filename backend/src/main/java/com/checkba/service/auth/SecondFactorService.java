package com.checkba.service.auth;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.sms.SmsAuthService;
import com.checkba.service.totp.TotpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 登录二次验证的唯一判定出口：密码通过之后还要不要再验一道、验哪一道、怎么验。
 *
 * <p>存在的理由是 {@code /login} 与 {@code /device-token} 两条密码入口必须走同一套判据——
 * 任何一条漏了都等于没设闸。新增第三条密码入口时也只接这一个服务。
 *
 * <h3>方式选择</h3>
 * TOTP 优先于短信：零成本、无国界、不受运营商报备与 SIM 交换影响。短信是大陆用户的
 * 习惯路径与未装认证器时的兜底。两者都没有则不拦（存量用户不受影响）。
 */
@Service
@RequiredArgsConstructor
public class SecondFactorService {

    public enum Method {
        /** 无需二次验证。 */
        NONE,
        /** 认证器 App（RFC 6238）。 */
        TOTP,
        /** 短信验证码。 */
        SMS
    }

    private final TotpService totpService;
    private final SmsAuthService smsAuthService;
    private final UserRepository userRepository;

    /** 该用户本次登录需要哪一道二次验证。 */
    public Method required(User user) {
        if (user == null) return Method.NONE;
        if (user.isTotpEnabled() && StringUtils.hasText(user.getTotpSecret())) {
            return Method.TOTP;
        }
        if (smsAuthService.requiresCode(user)) {
            return Method.SMS;
        }
        return Method.NONE;
    }

    /** 提示前端往哪儿看：短信回脱敏号码，TOTP 回空串（码在用户手机的 App 里）。 */
    public String target(User user) {
        return required(user) == Method.SMS ? SmsAuthService.maskPhone(user.getPhone()) : "";
    }

    /**
     * 校验二次验证码；不通过抛业务错误。NONE 时直接放行。
     *
     * <p>TOTP 走**时间片重放拦截**：同一枚码在其 30 秒窗口内只能用一次，
     * 否则肩窥或抓包拿到的码在有效期内可被重复使用。
     */
    public void verify(User user, String code) {
        switch (required(user)) {
            case TOTP -> verifyTotp(user, code);
            case SMS -> smsAuthService.verifyLoginCode(user, code);
            case NONE -> {
            }
        }
    }

    private void verifyTotp(User user, String code) {
        long step = totpService.verify(user.getTotpSecret(), code);
        if (step < 0) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        Long lastUsed = user.getTotpLastUsedStep();
        if (lastUsed != null && step <= lastUsed) {
            throw new IllegalArgumentException("该验证码已使用，请等待下一个验证码");
        }
        user.setTotpLastUsedStep(step);
        userRepository.save(user);
    }

    // ==================== 认证器绑定 ====================

    /** 开始绑定：生成密钥并落库（未启用），返回手工录入用的密钥与二维码 URI。 */
    public Setup startSetup(Long userId, String issuer) {
        User user = requireUser(userId);
        if (user.isTotpEnabled()) {
            throw new IllegalArgumentException("认证器已绑定，请先解绑再重新绑定");
        }
        String secret = totpService.newSecret();
        user.setTotpSecret(secret);
        user.setTotpLastUsedStep(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return new Setup(secret, totpService.provisioningUri(secret, user.getUsername(), issuer));
    }

    /** 完成绑定：验一次码证明 App 已配好，才真正启用。 */
    public void activate(Long userId, String code) {
        User user = requireUser(userId);
        if (!StringUtils.hasText(user.getTotpSecret())) {
            throw new IllegalArgumentException("尚未开始绑定认证器");
        }
        long step = totpService.verify(user.getTotpSecret(), code);
        if (step < 0) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        user.setTotpEnabled(true);
        user.setTotpLastUsedStep(step);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * 解绑认证器。**必须验一次当前码**——否则一个被短暂借用的已登录会话就能把
     * 二次验证摘掉，二次验证形同虚设。认证器丢了的走 {@link #resetByAdmin}。
     */
    public void disable(Long userId, String code) {
        User user = requireUser(userId);
        if (!user.isTotpEnabled()) {
            return;
        }
        if (totpService.verify(user.getTotpSecret(), code) < 0) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        clearTotp(user);
    }

    /** 管理员为丢失认证器的用户清除绑定（server 模式的兜底，避免永久锁死在门外）。 */
    public void resetByAdmin(Long targetUserId) {
        clearTotp(requireUser(targetUserId));
    }

    private void clearTotp(User user) {
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpLastUsedStep(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    /** 绑定所需的两样东西：手工录入的密钥、扫码用的 otpauth URI。 */
    public record Setup(String secret, String provisioningUri) {
    }
}
