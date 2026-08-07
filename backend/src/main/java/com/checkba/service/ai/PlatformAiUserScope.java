package com.checkba.service.ai;

import java.util.concurrent.Callable;

/**
 * 「这次 AI 调用花的是谁的额度」——平台通道的身份作用域（server 模式多租户）。
 *
 * <h3>为什么不是把 userId 一路当参数传</h3>
 * {@code ChatModelFactory.getChatModel} 在全仓有十余个调用点，其中
 * MemCellExtractor / ConversationSummarizer / AgenticRetriever / AiAssistantService
 * 的方法签名里根本没有 userId，穿参要改动整个 AI 编排领域的调用链。
 * 这里改用显式作用域：在少数几个手边就有 userId 的入口设置，同线程的后续调用自动继承。
 *
 * <h3>红线</h3>
 * ThreadLocal 不会跨线程池自动传递（池线程继承的是创建者而非提交者）。因此每一处
 * <b>跨线程提交</b>都必须用 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 显式重放。
 * 漏掉一处的后果被设计成「那条路报业务错误」而不是「那条路记错账」——
 * 见 {@link PlatformAiChannel#resolve}：多租户形态下缺身份一律拒绝，绝不回落机器级 key。
 * 错账是静默的，报错是能被测试和冒烟抓到的。
 */
public final class PlatformAiUserScope {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private PlatformAiUserScope() {}

    /** 当前线程的用户；未设置返回 null。 */
    public static Long current() {
        return CURRENT.get();
    }

    /** 在作用域内执行。嵌套安全：退出时恢复外层值而不是清空。 */
    public static void run(Long userId, Runnable body) {
        Long previous = CURRENT.get();
        set(userId);
        try {
            body.run();
        } finally {
            set(previous);
        }
    }

    public static <T> T call(Long userId, java.util.function.Supplier<T> body) {
        Long previous = CURRENT.get();
        set(userId);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    /** 捕获当前身份，供跨线程提交时重放。 */
    public static Runnable wrap(Runnable task) {
        Long captured = CURRENT.get();
        return () -> run(captured, task);
    }

    /** 捕获当前身份，供跨线程提交时重放（有返回值版本）。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Long captured = CURRENT.get();
        return () -> {
            Long previous = CURRENT.get();
            set(captured);
            try {
                return task.call();
            } finally {
                set(previous);
            }
        };
    }

    private static void set(Long userId) {
        if (userId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(userId);
        }
    }
}
