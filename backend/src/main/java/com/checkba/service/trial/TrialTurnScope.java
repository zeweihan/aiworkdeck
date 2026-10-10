// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 试用计量 v0.1.1：标记「这一次用户提交是否真的调用了云端平台通道」。
 *
 * <p>编排器在执行线程上用 {@link #run} 开一个作用域；{@code ChatModelFactory.platformApiKey()}
 * 每次取平台 key 时调 {@link #markPlatformUse()}。回合收尾时据此判断要不要上报——
 * 纯本地 / BYOK 回合不计次（规格 §1.4）。同一作用域内多次取 key（工具多跳、编排内重试）
 * 只是同一个布尔，天然「不另计」（规格 §1.5）。作用域外调用是 no-op。
 */
public final class TrialTurnScope {

    private static final ThreadLocal<AtomicBoolean> CURRENT = new ThreadLocal<>();

    private TrialTurnScope() {}

    /** 在作用域内执行 body；嵌套安全（退出恢复外层）。 */
    public static void run(AtomicBoolean flag, Runnable body) {
        AtomicBoolean previous = CURRENT.get();
        CURRENT.set(flag);
        try {
            body.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void markPlatformUse() {
        AtomicBoolean flag = CURRENT.get();
        if (flag != null) flag.set(true);
    }
}
