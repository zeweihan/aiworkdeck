// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SSE 事件名与领域文档「SSE 事件名清单」的对拍。
 *
 * <p><b>为什么要守</b>：SSE 事件名是<b>跨端公开契约</b>——桌面端 useAgentStream、
 * Office/WPS 任务窗格的 sse.js 各自按名字分派。领域文档里那份清单是唯一的人读索引，
 * 而「后端加了一个事件、文档没加」这件事不会有任何东西报错：
 * 下一个人照着文档写客户端，那个事件对他就不存在。
 *
 * <p>只做<b>单向</b>对拍（代码里发的都要在文档里）：文档里可以多出已经摘掉的旧名
 *（比如 wps_stream_data 那条双轨，dev-board#816 已摘），反过来不行。
 * 「摘掉的旧名不许回来」由 {@code EditorBridgeSingleDispatchTest} 守。
 */
class SseEventNameDocContractTest {

    /** 从 {@code sendRunEvent(guard, "x", …)} / {@code sseEmitterService.send(id, "x", …)} 里抠事件名。 */
    private static final Pattern EMIT = Pattern.compile(
            "(?:sendRunEvent\\s*\\(\\s*[A-Za-z0-9_.]+\\s*,|sseEmitterService\\.send\\s*\\(\\s*[^,]+,)\\s*\"([a-z_]+)\"");

    /** 事件名是动态拼的（转发第三方载荷）或只是转发形参，不在本契约范围内。 */
    private static final Set<String> NOT_A_LITERAL_EVENT = Set.of("event", "name");

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(".claude/agents/ai-chat.md"))) return dir;
            dir = dir.getParent();
        }
        return fail("找不到 .claude/agents/ai-chat.md——它是 SSE 事件名清单的唯一人读索引");
    }

    private static String docText() throws IOException {
        return Files.readString(repoRoot().resolve(".claude/agents/ai-chat.md"), StandardCharsets.UTF_8);
    }

    private static Set<String> emittedEventNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Path src = repoRoot().resolve("backend/src/main/java/com/checkba");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = EMIT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String name = m.group(1);
                    if (!NOT_A_LITERAL_EVENT.contains(name)) names.add(name);
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("后端发出的每个 SSE 事件名都在 ai-chat.md 的清单里")
    void everyEmittedEventIsDocumented() throws IOException {
        String doc = docText();
        int listStart = doc.indexOf("## SSE 事件名清单");
        assertTrue(listStart >= 0, "ai-chat.md 必须保留「## SSE 事件名清单」这一节");
        String list = doc.substring(listStart, Math.min(doc.length(), listStart + 4000));

        Set<String> emitted = emittedEventNames();
        assertTrue(emitted.size() > 10, "抠出来的事件名太少，正则大概已经失配：" + emitted);

        StringBuilder missing = new StringBuilder();
        for (String name : emitted) {
            if (!list.contains(name)) missing.append(name).append(' ');
        }
        assertTrue(missing.isEmpty(),
                "这些事件后端在发、清单里却没有：" + missing
                        + "——下一个照着文档写客户端的人会以为它不存在");
    }

    @Test
    @DisplayName("context_notice 确实是这条路上发出来的，不是只写在文档里")
    void contextNoticeIsActuallyEmitted() throws IOException {
        assertTrue(emittedEventNames().contains("context_notice"),
                "附件降级/截断/丢弃的可见性全靠它（dev-board#801 K21 ⑦）");
        assertFalse(docText().indexOf("context_notice") < 0, "清单里也要有");
    }
}
