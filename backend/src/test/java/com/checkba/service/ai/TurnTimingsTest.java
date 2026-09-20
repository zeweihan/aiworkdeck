// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分段计时的「默认零开销、零输出」契约（dev-board#750）。
 *
 * <p>这东西挂在每一轮对话的关键路径上，而且 {@code mark} 会被调十几次。默认必须做到：
 * 一行日志都不打、一个对象都不分配。有人把它改成「无条件记录、只在 done 时判级别」
 * 就会在生产上凭空多出每轮十几次 {@code System.nanoTime} 与字符串拼接。
 */
class TurnTimingsTest {

    @Test
    @DisplayName("DEBUG 关闭时：不打日志，且拿到的是共享的空操作实例（零分配）")
    void disabledByDefault() {
        Logger log = mock(Logger.class);
        when(log.isDebugEnabled()).thenReturn(false);

        TurnTimings a = TurnTimings.start(log, "prep", "conv-1");
        TurnTimings b = TurnTimings.start(log, "round", "conv-2");
        assertSame(a, b, "DEBUG 关闭时必须复用同一个空操作实例，不能每轮 new 一个");

        a.mark("step");
        a.mark("step", 200);
        a.done(log);

        verify(log, never()).debug(anyString(), any(Object[].class));
        verify(log, never()).debug(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("DEBUG 打开时：打一条带各分段的汇总行")
    void reportsSegmentsWhenDebugEnabled() {
        StringBuilder captured = new StringBuilder();
        Logger log = mock(Logger.class);
        when(log.isDebugEnabled()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> {
            // 不能写 String.valueOf(inv.getArgument(i))：泛型 <T> T 会被重载决议推成 char[]，
            // 运行时 ClassCastException（ai-chat.md「已知地雷」里的 Mockito 陷阱）
            for (Object arg : inv.getArguments()) {
                captured.append('|').append(java.util.Objects.toString(arg));
            }
            return null;
        }).when(log).debug(anyString(), any(), any(), any(), any());

        TurnTimings t = TurnTimings.start(log, "prep", "conv-x");
        t.mark("saveUserMsg");
        t.mark("assemble", 42);
        t.done(log);

        String line = captured.toString();
        assertTrue(line.contains("prep"), line);
        assertTrue(line.contains("conv-x"), line);
        assertTrue(line.contains("saveUserMsg="), line);
        assertTrue(line.contains("assemble="), line);
        assertTrue(line.contains("(42)"), "带计数的分段要把规模一并记上：" + line);
    }
}
