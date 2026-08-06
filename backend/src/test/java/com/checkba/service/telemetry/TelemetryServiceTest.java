package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 锁定隐私红线：白名单外字段（文件名/路径/消息文本等）必须被剔除，
 * 未知事件整条拒绝，采集层任何异常不外溢到业务调用方。
 */
class TelemetryServiceTest {

    @TempDir
    Path dir;

    TelemetryEventRepository repo;
    TelemetryService svc;

    @BeforeEach
    void setup() {
        repo = mock(TelemetryEventRepository.class);
        svc = new TelemetryService(repo, new InstallIdentityService(dir.toString()), "0.0.0-test");
    }

    @Test
    void legalEventIsPersistedWithWhitelistedAttrsOnly() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("toolName", "doc_replace_text");
        attrs.put("success", true);
        attrs.put("durationMs", 120);
        // 隐私红线字段：必须被剔除
        attrs.put("fileName", "某某公司股权转让协议.docx");
        attrs.put("message", "帮我审查这份合同");
        attrs.put("path", "/Users/x/案件/秘密.docx");

        svc.record("ai.tool", attrs);
        svc.flush();

        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        String json = cap.getValue().getAttrs();
        assertTrue(json.contains("doc_replace_text"));
        assertFalse(json.contains("股权转让"));
        assertFalse(json.contains("审查"));
        assertFalse(json.contains("/Users"));
        assertEquals("ai.tool", cap.getValue().getEventName());
        assertEquals("0.0.0-test", cap.getValue().getAppVersion());
        assertTrue(svc.droppedCount() >= 3);
    }

    @Test
    void unknownEventNameIsRejectedEntirely() throws Exception {
        svc.record("made.up.event", Map.of("x", 1));
        svc.flush();
        verify(repo, never()).save(any());
        assertEquals(1, svc.droppedCount());
    }

    @Test
    void overlongStringValueIsDropped() throws Exception {
        svc.record("ai.tool", Map.of("toolName", "x".repeat(65)));
        svc.flush();
        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getAttrs());
    }

    @Test
    void convKeyIsDerivedAndRawIdNeverStored() throws Exception {
        String conv = "conv-1754460000000";
        svc.recordConv("ai.turn", conv, Map.of("outcome", "FINISHED"));
        svc.flush();
        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        String key = cap.getValue().getConvKey();
        assertNotNull(key);
        assertEquals(16, key.length());
        assertFalse(key.contains("1754460000000"));
    }

    @Test
    void repositoryFailureNeverPropagates() throws Exception {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> {
            svc.record("file.changed", null);
            svc.flush();
        });
    }
}
