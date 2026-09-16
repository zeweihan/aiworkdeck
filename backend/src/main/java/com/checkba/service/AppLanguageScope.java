// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import java.util.Locale;

/**
 * 「这一次请求 / 这一轮对话该说哪种语言」——应用语言的请求级作用域。
 *
 * <h3>为什么需要它</h3>
 * {@link AppLanguageService} 的权威值落在 system_setting 的 {@code app.language} 上，那是
 * <b>全局单值</b>——单机桌面版里「应用语言」本来就是一台机器一个值，这条链一直够用。
 * 云后端（{@code local-mode=false}）是多租户：同一个进程同时服务中文与英文用户，
 * 谁也不能把那个全局值改成自己的语言（改一次全体跟着翻）。于是把语言做成<b>覆盖层</b>：
 * 请求/轮次声明了语言就用声明的，没声明就回落全局设置——桌面端一个字节都不受影响。
 *
 * <h3>红线（与 {@link com.checkba.service.ai.PlatformAiUserScope} 同款）</h3>
 * ThreadLocal 不跨线程池传递。HTTP 线程上的覆盖由 {@code AppLanguageRequestFilter} 设置，
 * 而 {@code POST /api/agent/chat} 的编排循环跑在 taskExecutor 池线程上——那一路的语言必须由
 * {@code AgentChatRequest.appLanguage} 随请求体带过去、在 {@code AgentOrchestrator.launchTurn}
 * 重新建立作用域。漏掉的后果是<b>静默</b>的：英文用户拿到中文回答，不报错。
 */
public final class AppLanguageScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AppLanguageScope() {
    }

    /** 当前线程声明的语言（已归一为 zh-CN / en-US）；未声明返回 null。 */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 归一化调用方给的语言串：{@code zh-CN} / {@code en-US} 原样，另外容忍
     * {@code zh} / {@code en} / {@code zh_CN} / {@code en-GB} 这些客户端常见写法。
     * 无法识别（含 null / 空白 / {@code ja-JP}）返回 null = 不覆盖，回落全局设置。
     *
     * <p>刻意只按前两位语言码判定而不是整串匹配：插件端的界面语言只有 zh / en 两档，
     * 而 Office / 浏览器给出的 locale 形态五花八门（{@code en-GB}、{@code zh-Hant-TW}），
     * 整串匹配会把它们全判成「不认识」，于是英文界面静默拿到中文回答。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String primary = trimmed.replace('_', '-').toLowerCase(Locale.ROOT);
        int dash = primary.indexOf('-');
        if (dash > 0) primary = primary.substring(0, dash);
        if ("zh".equals(primary)) return AppLanguageService.ZH_CN;
        if ("en".equals(primary)) return AppLanguageService.EN_US;
        return null;
    }

    /**
     * 在作用域内执行。嵌套安全：退出时恢复外层值而不是清空。
     * lang 归一后为 null（未声明 / 不认识）时等于不覆盖——照样恢复外层值，
     * 这样一条不带语言的内层调用不会把外层的声明抹掉。
     */
    public static void run(String lang, Runnable body) {
        String previous = CURRENT.get();
        String normalized = normalize(lang);
        set(normalized != null ? normalized : previous);
        try {
            body.run();
        } finally {
            set(previous);
        }
    }

    public static <T> T call(String lang, java.util.function.Supplier<T> body) {
        String previous = CURRENT.get();
        String normalized = normalize(lang);
        set(normalized != null ? normalized : previous);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    private static void set(String lang) {
        if (lang == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(lang);
        }
    }
}
