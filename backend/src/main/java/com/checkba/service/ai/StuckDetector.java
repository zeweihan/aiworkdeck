package com.checkba.service.ai;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 原地打转检测（对标 OpenHands StuckDetector）：单次 Agent 运行内跟踪最近若干次工具调用签名，
 * 识别周期性重复。
 *
 * <p>为什么要滑动窗口：原先只存一个 lastCallSignature，只能识别「A/A/A」这种连续同参重复，
 * 模型一旦退化成「读文件 → 写同样内容 → 再读 → 再写」的 A/B/A/B 交替，守卫全程无感，
 * 一路空转到步数预算耗尽。
 *
 * <p>为什么先干预后熔断：第一次检出就拦截会误伤「参数确实该重复、只是上一次失败」的正常重试；
 * 而重复往往是模型忘了目标，一条末位提醒就能拉回来。所以第一次只提醒（工具照常执行），
 * 第二次才拒绝执行。
 */
public class StuckDetector {

    /** 签名窗口长度：能同时容下 A/A/A（3）与 A/B/A/B（4）两种模式并留出余量 */
    static final int WINDOW_SIZE = 6;
    /** 连续同参重复达到该次数即判定打转（与加固前的 MAX_IDENTICAL_TOOL_CALLS 同口径） */
    static final int IDENTICAL_RUN = 3;

    public enum Verdict {
        /** 未检出打转，正常执行 */
        OK,
        /** 首次检出：工具照常执行，但要往消息末位追加一条换思路提醒 */
        INTERVENE,
        /** 再次检出：拒绝执行，把守卫反馈当作工具结果回给模型 */
        CIRCUIT_BREAK
    }

    private final Deque<String> window = new ArrayDeque<>(WINDOW_SIZE);
    private boolean intervened;
    private String lastPattern;

    /**
     * 记录一次工具调用并给出裁决。
     *
     * @return OK / INTERVENE / CIRCUIT_BREAK
     */
    public Verdict record(String toolName, String argsJson) {
        String signature = toolName + "|" + (argsJson == null ? "" : argsJson);
        if (window.size() >= WINDOW_SIZE) {
            window.removeFirst();
        }
        window.addLast(signature);

        String pattern = detectPattern();
        if (pattern == null) {
            return Verdict.OK;
        }
        lastPattern = pattern;
        if (!intervened) {
            intervened = true;
            return Verdict.INTERVENE;
        }
        return Verdict.CIRCUIT_BREAK;
    }

    /** 最近一次检出的模式描述（给模型/日志看的中文），未检出过时为 null。 */
    public String lastPattern() {
        return lastPattern;
    }

    /**
     * 两种模式：连续同参重复（A/A/A）与二元交替（A/B/A/B）。
     * 命中返回中文描述，否则 null。
     */
    private String detectPattern() {
        String[] recent = window.toArray(new String[0]);
        int n = recent.length;

        if (n >= IDENTICAL_RUN) {
            boolean allSame = true;
            for (int i = n - IDENTICAL_RUN; i < n - 1; i++) {
                if (!recent[i].equals(recent[i + 1])) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                return "连续 " + IDENTICAL_RUN + " 次以完全相同的参数调用 " + toolOf(recent[n - 1]);
            }
        }

        if (n >= 4) {
            String a = recent[n - 4];
            String b = recent[n - 3];
            if (!a.equals(b) && a.equals(recent[n - 2]) && b.equals(recent[n - 1])) {
                return toolOf(a).equals(toolOf(b))
                        ? "在 " + toolOf(a) + " 的两组参数之间反复交替调用"
                        : "在 " + toolOf(a) + " 与 " + toolOf(b) + " 之间反复交替调用";
            }
        }
        return null;
    }

    private static String toolOf(String signature) {
        int sep = signature.indexOf('|');
        return sep > 0 ? signature.substring(0, sep) : signature;
    }
}
