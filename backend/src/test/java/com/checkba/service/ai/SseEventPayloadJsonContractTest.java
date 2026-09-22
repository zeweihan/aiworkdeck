// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SSE 载荷必须是调用方<b>自己序列化好的字符串</b>——{@code SseEmitterService.send} 不替谁转 JSON。
 *
 * <p>病灶（#663，2026-08-30）：{@code send} 从 {@code SseEmitter.event().data(Object)}
 *（Spring 用 Jackson 转换器序列化）改成了 {@code String.valueOf(data)}。全仓四十多个调用点里
 * 只有 {@code doc_stream_data} / {@code wps_stream_data} 这两行传的是裸 {@code Map.of(...)}，
 * 于是它们的载荷从 {@code {"content":"…"}} 变成了 Java 的 {@code Map.toString()} —— {@code {content=…}}。
 * 前端 {@code useAgentStream} 的 {@code JSON.parse} 必然抛错，被 catch 吞成一行 console.error：
 * AI 流式写入新建文档的正文一个字都到不了编辑器，而气泡上还挂着「正在向文档流式写入内容…」，
 * 前后端谁都不报错（正是 dev-board#465 描述的那个症状）。
 *
 * <p><b>为什么修在调用方而不是修在 send 里</b>：① 其余全部调用点早就是「自己 writeValueAsString
 * 之后再交给 send」，让 send 兼容裸对象等于让两套约定并存，下一次违例照样静默；
 * ② {@code send} 里的 payload 还要喂给断点续传的环形缓冲（按 {@code data().length()} 算字节上限）
 * 与 {@code Last-Event-ID} 游标，改它等于一次性改掉所有事件名的缓冲语义；
 * ③ 约定保持「send 只收字符串」，违例才能像下面这样在测试里当场红。
 */
class SseEventPayloadJsonContractTest {

    /** {@code sseEmitterService.send(x, "event", <data>)} 的第三个实参。 */
    private static final Pattern SEND_CALL = Pattern.compile(
            "sseEmitterService\\.send\\s*\\(\\s*[^,()]+,\\s*\"[a-z_]+\"\\s*,\\s*([^;]*?)\\)\\s*;",
            Pattern.DOTALL);

    /** 裸容器字面量：交给 String.valueOf 就成了 toString，不是 JSON。 */
    private static final List<String> BARE_CONTAINERS = List.of(
            "Map.of(", "Map.ofEntries(", "List.of(", "new HashMap", "new ArrayList", "new LinkedHashMap");

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(".claude/agents/ai-chat.md"))) return dir;
            dir = dir.getParent();
        }
        return fail("找不到仓库根");
    }

    @Test
    @DisplayName("没有任何 sseEmitterService.send 调用把裸 Map / List 当载荷交出去")
    void noCallerHandsSendABareContainer() throws IOException {
        Path src = repoRoot().resolve("backend/src/main/java/com/checkba");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = SEND_CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    scanned++;
                    String data = m.group(1).trim();
                    // writeValueAsString(...) 包在外面就没问题，哪怕里面是 Map.of(...)
                    if (data.contains("writeValueAsString")) continue;
                    for (String bare : BARE_CONTAINERS) {
                        if (data.startsWith(bare) || data.startsWith("java.util." + bare)) {
                            offenders.add(file.getFileName() + " → " + data);
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 10, "正则大概已经失配，只扫到 " + scanned + " 个 send 调用");
        assertTrue(offenders.isEmpty(),
                "这些 send 调用把裸容器当载荷交出去了，客户端 JSON.parse 会抛错："
                        + offenders + "——先 writeValueAsString 再发");
    }

    @Test
    @DisplayName("send 的约定就是「只收字符串」：裸 Map 进去出来的是 toString，不是 JSON")
    void sendDoesNotSerializeForYou() {
        // 这条不是在为现状背书，而是把「为什么上面那条规则必须存在」钉死：
        // 只要 send 还在用 String.valueOf，裸对象就永远是静默的坏载荷。
        assertEquals("{content=正文}", String.valueOf(Map.of("content", "正文")),
                "String.valueOf(Map) 一旦变成合法 JSON，上面那条源码扫描就可以退休了");
    }
}
