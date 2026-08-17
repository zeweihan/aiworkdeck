package com.checkba.config;

import com.checkba.service.sms.SmsAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 强制手机号登录的启动期不变式。
 *
 * 这条闸存在的唯一理由：**开了强制手机号登录、而短信网关是暗的，等于所有人都登不进来。**
 * 那种情况下静默启动是最坏的选择——服务看起来是健康的，用户全被挡在门外，
 * 排查方向会跑到前端和网络上去。宁可拒绝启动，让部署的人当场看见。
 *
 * 参照 {@code PlatformAiKeyCipher} 缺密钥即拒启的同一口径。
 */
@Component
public class PhoneLoginGuard {

    private final boolean required;

    public PhoneLoginGuard(
            SmsAuthService smsAuthService,
            @Value("${auth.phone-login-required:false}") boolean required,
            @Value("${security.local-mode:false}") boolean localMode) {

        this.required = required && !localMode;

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

    /** 供登录链路判断是否关闭密码登录。 */
    public boolean isRequired() {
        return required;
    }
}
