package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SSE 载荷是手工拼的 JSON 字符串，转义漏一个字符，整条事件就在浏览器里 JSON.parse 失败。
 *
 * <p>病灶：escapeJson 只处理 {@code \ " \n \r}，而 JSON 规范要求 U+0000..U+001F
 * 全部转义。模型正文里带一个真制表符（写 Makefile / Go / 缩进代码块时是常态）就会
 * 生成非法 JSON：前端 useAgentStream 的 text_delta 分支 parse 失败后回落
 * {@code processTextStream(dataStr)}，把整段 {"content":"..."} 信封当正文渲染给用户看；
 * artifact 分支则整条事件被丢弃（气泡永远卡在加载态）。
 */
class AgentStreamJsonEscapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String envelope(String raw) {
        return "{\"content\":\"" + AgentStreamHandler.escapeJson(raw) + "\"}";
    }

    @Test
    @DisplayName("正文里的制表符必须转义，否则 text_delta 载荷不是合法 JSON")
    void tabInModelOutputStaysParseable() throws Exception {
        String raw = "func main() {\n\tprintln(1)\n}";
        String payload = envelope(raw);
        assertEquals(raw, mapper.readTree(payload).get("content").asText());
    }

    @Test
    @DisplayName("U+0000..U+001F 全区间都要转义（JSON 规范要求）")
    void allControlCharactersAreEscaped() {
        for (char c = 0; c < 0x20; c++) {
            String raw = "a" + c + "b";
            String payload = envelope(raw);
            final char probe = c;
            assertDoesNotThrow(() -> {
                assertEquals(raw, mapper.readTree(payload).get("content").asText(),
                        "U+" + String.format("%04X", (int) probe) + " 未被正确转义");
            }, "U+" + String.format("%04X", (int) probe) + " 让载荷变成非法 JSON: " + payload);
        }
    }

    @Test
    @DisplayName("既有的反斜杠/引号/换行转义不能被改坏")
    void existingEscapesStillWork() throws Exception {
        String raw = "他说\"C:\\tmp\"\r\n第二行";
        assertEquals(raw, mapper.readTree(envelope(raw)).get("content").asText());
    }
}
