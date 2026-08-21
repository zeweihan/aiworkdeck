package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.util.style.StyleProfiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * doc_open_file 追发画像（dev-board#111）：项目画像不是 house-default 才在打开指令里带 styleProfile，
 * 前端在该文件的编辑器就绪后追发 set_style_profile。house-default 时不带——worker 默认就是它。
 */
class EditorBridgeServiceStyleProfileTest {

    private static ProjectFile file() {
        ProjectFile f = new ProjectFile();
        f.setId(100L);
        f.setName("报告.docx");
        f.setFileType("docx");
        f.setProjectId(7L);
        return f;
    }

    private static JsonNode firstOpenPayload(SseEmitterService sse) throws Exception {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(sse, Mockito.atLeastOnce()).send(eq("conv"), eq("client_action"), cap.capture());
        for (String payload : cap.getAllValues()) {
            JsonNode n = new ObjectMapper().readTree(payload);
            if ("doc_open_file".equals(n.path("action").asText())) return n;
        }
        throw new AssertionError("没抓到 doc_open_file 载荷: " + cap.getAllValues());
    }

    @Test
    @DisplayName("项目画像非 house-default：doc_open_file 带 styleProfile（已 merge 到 HOUSE 之上的完整画像）")
    void carriesProfileWhenCustom() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService svc = new EditorBridgeService(sse, new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        StyleProfileResolver resolver = mock(StyleProfileResolver.class);
        when(resolver.resolve(eq(7L), any())).thenReturn(StyleProfiles.houseDefault().merge(StyleProfiles.parse(
                "{\"schemaVersion\":1,\"body\":{\"firstLineIndent\":{\"value\":0,\"unit\":\"pt\"}}}")));
        svc.setStyleProfileResolver(resolver);
        svc.setCurrentConversationId("conv");

        svc.sendOpenFileAction(file());

        JsonNode open = firstOpenPayload(sse);
        assertTrue(open.has("styleProfile"), open.toString());
        assertEquals(0, open.path("styleProfile").path("body").path("firstLineIndent").path("value").asInt());
        assertEquals("楷体_GB2312", open.path("styleProfile").path("body").path("font").path("eastAsia").asText(),
                "缺省叶子由 house-default 补齐后再下发");
    }

    @Test
    @DisplayName("项目画像就是 house-default：doc_open_file 不带 styleProfile")
    void omitsProfileWhenHouseDefault() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService svc = new EditorBridgeService(sse, new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        StyleProfileResolver resolver = mock(StyleProfileResolver.class);
        when(resolver.resolve(eq(7L), any())).thenReturn(StyleProfiles.houseDefault());
        svc.setStyleProfileResolver(resolver);
        svc.setCurrentConversationId("conv");

        svc.sendOpenFileAction(file());

        assertFalse(firstOpenPayload(sse).has("styleProfile"));
    }

    @Test
    @DisplayName("没有解析器（旧构造 / 测试）：照常打开，不带 styleProfile、不抛")
    void worksWithoutResolver() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService svc = new EditorBridgeService(sse, new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        svc.setCurrentConversationId("conv");
        svc.sendOpenFileAction(file());
        assertFalse(firstOpenPayload(sse).has("styleProfile"));
    }
}
