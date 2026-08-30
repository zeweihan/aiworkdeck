package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * text_delta 信封的转义（dev-board#288）。
 *
 * <p><b>为什么单独再来一份</b>：仓库里**有两个手写的 JSON 转义**。
 * {@link AgentStreamHandler#escapeJson} 早先已经补齐到全区间（见
 * {@code AgentStreamJsonEscapeTest}），但 {@code AgentOrchestrator.sendTextDelta}
 * 一直另有一份自己的四条 replace（只处理 {@code \ " \n \r}），从没被那组用例覆盖到——
 * 同一个病灶在第二个地方原样活着。模型正文里带一个真制表符（法律文书里的表格内容、
 * 工具回显都是常态）就会生成非法 JSON：插件端 {@code JSON.parse} 失败后按原文渲染，
 * 用户看到的是 <code>{"content":"…</code> 这一串信封本身；桌面端同理。
 *
 * <p>现在改成用 Jackson 序列化，本用例把它钉住。把 {@code jsonContentEnvelope}
 * 换回手写 replace 就会转红。
 */
class AgentOrchestratorTextDeltaEscapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("正文里的制表符必须转义，否则 text_delta 载荷不是合法 JSON")
    void tabInModelOutputStaysParseable() throws Exception {
        String raw = "项目\t金额\n合计\t100 万元";
        String payload = AgentOrchestrator.jsonContentEnvelope(raw);
        assertEquals(raw, mapper.readTree(payload).get("content").asText());
    }

    @Test
    @DisplayName("U+0000..U+001F 全区间都要转义（JSON 规范要求）")
    void allControlCharactersAreEscaped() {
        for (char c = 0; c < 0x20; c++) {
            String raw = "a" + c + "b";
            String payload = AgentOrchestrator.jsonContentEnvelope(raw);
            final char probe = c;
            assertDoesNotThrow(() -> assertEquals(raw, mapper.readTree(payload).get("content").asText(),
                            "U+" + String.format("%04X", (int) probe) + " 未被正确转义"),
                    "U+" + String.format("%04X", (int) probe) + " 让载荷变成非法 JSON: " + payload);
        }
    }

    @Test
    @DisplayName("反斜杠/引号/换行/中文照旧，null 不炸")
    void existingEscapesStillWork() throws Exception {
        String raw = "他说\"C:\\tmp\"\r\n第二行：甲方（乙方）";
        assertEquals(raw, mapper.readTree(AgentOrchestrator.jsonContentEnvelope(raw)).get("content").asText());
        assertEquals("", mapper.readTree(AgentOrchestrator.jsonContentEnvelope(null)).get("content").asText());
    }
}
