package com.checkba.config;

import com.checkba.model.entity.User;
import com.checkba.service.sms.SmsAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 强制手机号登录的策略与启动期不变式。
 *
 * <p><b>启动期</b>：**开了强制手机号登录、而短信网关是暗的，等于所有人都登不进来。**
 * 那种情况下静默启动是最坏的选择——服务看起来是健康的，用户全被挡在门外，
 * 排查方向会跑到前端和网络上去。宁可拒绝启动，让部署的人当场看见。
 * 参照 {@code PlatformAiKeyCipher} 缺密钥即拒启的同一口径。
 *
 * <p><b>运行期</b>：{@link #gateFor} 是存量账号补绑手机号的三态闸（spec §5）。
 * 口径与官网 {@code lib/phone-policy.ts} 的 {@code gateForUser()} 一一对应，两边别漂。
 * 接线在 {@code AuthController} 的三条会签发凭据的登录路径上。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-17-phone-login-design.md}。
 */
@Component
public class PhoneLoginGuard {

    private static final Logger log = LoggerFactory.getLogger(PhoneLoginGuard.class);

    /**
     * 默认硬期限。改这个值要同步官网 {@code lib/phone-policy.ts} 的
     * {@code DEFAULT_BINDING_DEADLINE}——两边漂了就会出现「官网说还能用到 X 日、
     * 后端 X-30 日就把人拒了」。
     */
    static final String DEFAULT_BINDING_DEADLINE = "2026-09-30";

    /** 人工通道。被锁在门外的用户只看得到这一个出口，改之前先确认有人值守。 */
    public static final String SUPPORT_EMAIL = "hi@aiworkdeck.com";

    /** 本次登录该怎么处理这个账号。 */
    public enum PhoneGate {
        /** 放行（没开强制、或已绑号）。 */
        OK,
        /** 放行，但客户端要立刻弹不可跳过的强制补绑（期限内、未绑号）。 */
        MUST_BIND,
        /** 拒登（期限后、未绑号）。 */
        BLOCKED
    }

    private final boolean required;
    private final LocalDate bindingDeadline;

    public PhoneLoginGuard(
            SmsAuthService smsAuthService,
            @Value("${auth.phone-login-required:false}") boolean required,
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${auth.phone-binding-deadline:" + DEFAULT_BINDING_DEADLINE + "}") String bindingDeadline) {

        this.required = required && !localMode;
        this.bindingDeadline = parseDeadline(bindingDeadline);

        // local-mode（桌面端单机免登）根本没有登录环节，这条闸不适用
        if (!this.required) {
            return;
        }
        if (!smsAuthService.active()) {
            throw new IllegalStateException(
                    "开启强制手机号登录（auth.phone-login-required=true）时短信网关必须可用："
                            + "当前 sms.enabled=false 或未配置 SMS_ACCESS_KEY_ID/SECRET，"
                            + "此时启动会导致所有用户都无法登录。请注入短信密钥，"
                            + "或把 auth.phone-login-required 设为 false。");
        }
    }

    /**
     * 期限格式非法时**回落默认值而不是放行**——把 deadline 配错就无限期延期，
     * 那是最容易被忽略的静默失效。
     */
    private static LocalDate parseDeadline(String raw) {
        try {
            return LocalDate.parse(raw == null ? DEFAULT_BINDING_DEADLINE : raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("auth.phone-binding-deadline 格式非法（{}），回落默认值 {}", raw, DEFAULT_BINDING_DEADLINE);
            return LocalDate.parse(DEFAULT_BINDING_DEADLINE);
        }
    }

    /** 供登录链路判断是否启用手机号闸（local-mode 恒为 false）。 */
    public boolean isRequired() {
        return required;
    }

    /** 补绑期截止日；当天仍算期限内。 */
    public LocalDate bindingDeadline() {
        return bindingDeadline;
    }

    /** 存量账号在本次登录时该怎么处理。 */
    public PhoneGate gateFor(User user) {
        return gateFor(user, LocalDate.now());
    }

    /** 同上，注入「今天」便于按期限前后取值测试，不依赖机器当前日期。 */
    PhoneGate gateFor(User user, LocalDate today) {
        if (!required) return PhoneGate.OK;
        String phone = user == null ? null : user.getPhone();
        if (phone != null && !phone.isBlank()) return PhoneGate.OK;
        return today.isAfter(bindingDeadline) ? PhoneGate.BLOCKED : PhoneGate.MUST_BIND;
    }
}
