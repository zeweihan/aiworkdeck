package com.checkba.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 应用语言（zh-CN / en-US）。单机产品是全局设置：前端语言设置写透到这里，
 * 后端凡是产出用户可见文案 / 选择 system prompt 语言的地方统一从这里取值。
 * 默认 zh-CN——存量安装升级后行为不变；英文默认值由前端首启猜测后写入。
 */
@Service
@RequiredArgsConstructor
public class AppLanguageService {

    public static final String KEY = "app.language";
    public static final String ZH_CN = "zh-CN";
    public static final String EN_US = "en-US";
    private static final Set<String> SUPPORTED = Set.of(ZH_CN, EN_US);

    private final SystemSettingService settings;

    /**
     * 登记 LangText 静态桥。刻意用 @PostConstruct 而不是构造器：只有 Spring 容器里的
     * 这个实例才该成为全局指针，单测里 new 出来的实例不登记（否则跨测试类污染静态状态）。
     */
    @PostConstruct
    void registerLangTextBridge() {
        LangText.register(this);
    }

    public String language() {
        String v = settings.get(KEY, ZH_CN);
        // Set.of 的 contains(null) 会抛 NPE，先挡掉 null
        return v != null && SUPPORTED.contains(v) ? v : ZH_CN;
    }

    public boolean isEnglish() {
        return EN_US.equals(language());
    }

    /** 非法值静默忽略（保持现语言），返回生效值。 */
    public String setLanguage(String lang) {
        if (lang != null && SUPPORTED.contains(lang)) {
            settings.set(KEY, lang);
        }
        return language();
    }
}
