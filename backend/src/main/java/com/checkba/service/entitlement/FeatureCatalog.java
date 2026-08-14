package com.checkba.service.entitlement;

import com.checkba.service.LangText;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 中央功能目录（Spec §6）。
 *
 * 这里是桌面端**唯一**的 feature 名字来源：新增可解锁功能先在这里加常量，
 * 再在业务处用 {@link EntitlementService#isEnabled(String)} 判定，禁止在各处硬写字符串。
 *
 * 命名必须与官网 {@code doc/desktop-contract.md}「权益命名」一节逐字一致——
 * 官网生成兑换码时按同一张表做白名单校验，对不上就是发出去的码兑不了。
 *
 * 付费 Skill / 插件的权益名形如 {@code skill:<id>} / {@code plugin:<id>}，是动态的，
 * 不在本目录里枚举；{@link EntitlementService#isEnabled(String)} 对任意字符串都可用。
 */
public final class FeatureCatalog {

    /** 应用本体已解锁（试用码离线解锁或账户 Key 解锁）。 */
    public static final String APP_UNLOCKED = "app.unlocked";

    /** 无线剪贴板无限版：免费额度为最多回溯 20 条且保留 3 天。 */
    public static final String CLIPBOARD_UNLIMITED = "clipboard.unlimited";

    /** Stage 文件缓存区无限版：免费额度为最多 20 个文件、总量 500MB。 */
    public static final String STAGE_UNLIMITED = "stage.unlimited";

    /** 预留：整体 Pro 订阅。本轮不实现订阅状态机，仅占位以免后续改名。 */
    public static final String PLAN_PRO = "plan.pro";

    /** feature -> 面向用户的中文名。插入顺序即 GET /api/entitlements 的返回顺序。 */
    private static final Map<String, String> DISPLAY_NAMES;

    /** feature -> 面向用户的英文名，与 {@link #DISPLAY_NAMES} 一一对应，惰性按 {@link LangText#isEnglish()} 二选一。 */
    private static final Map<String, String> DISPLAY_NAMES_EN;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(APP_UNLOCKED, "应用解锁");
        names.put(CLIPBOARD_UNLIMITED, "无线剪贴板无限版");
        names.put(STAGE_UNLIMITED, "文件缓存区无限版");
        names.put(PLAN_PRO, "Pro 订阅");
        DISPLAY_NAMES = Collections.unmodifiableMap(names);

        Map<String, String> namesEn = new LinkedHashMap<>();
        namesEn.put(APP_UNLOCKED, "App Unlocked");
        namesEn.put(CLIPBOARD_UNLIMITED, "Wireless Clipboard Unlimited Edition");
        namesEn.put(STAGE_UNLIMITED, "File Staging Area Unlimited Edition");
        namesEn.put(PLAN_PRO, "Pro Subscription");
        DISPLAY_NAMES_EN = Collections.unmodifiableMap(namesEn);
    }

    /** 目录内全部 feature。 */
    public static Set<String> all() {
        return DISPLAY_NAMES.keySet();
    }

    /** 面向用户的名字（按应用语言二选一）；未知 feature 原样返回。 */
    public static String displayName(String feature) {
        return LangText.of(
                DISPLAY_NAMES.getOrDefault(feature, feature),
                DISPLAY_NAMES_EN.getOrDefault(feature, feature));
    }

    public static boolean isKnown(String feature) {
        return DISPLAY_NAMES.containsKey(feature);
    }

    private FeatureCatalog() {}
}
