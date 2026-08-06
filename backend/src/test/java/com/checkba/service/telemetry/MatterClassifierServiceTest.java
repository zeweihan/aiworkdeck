package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.ChatModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MatterClassifierServiceTest {

    @TempDir
    Path dir;

    TelemetryEventRepository repo;
    ChatModelFactory factory;
    ChatLanguageModel model;
    SystemSettingService settingStore;
    MatterClassifierService svc;
    TelemetryService telemetry;

    @BeforeEach
    void setup() {
        repo = mock(TelemetryEventRepository.class);
        telemetry = new TelemetryService(repo, new InstallIdentityService(dir.toString()), "test");
        factory = mock(ChatModelFactory.class);
        model = mock(ChatLanguageModel.class);
        when(factory.getChatModel(anyString())).thenReturn(model);
        settingStore = mock(SystemSettingService.class);
        // 默认：rollup 开关开
        when(settingStore.get(eq(TelemetrySettings.KEY_ROLLUP), anyString())).thenReturn("true");
        svc = new MatterClassifierService(factory, telemetry, new TelemetrySettings(settingStore),
                "test/model");
    }

    private String capturedCategory() throws Exception {
        svc.flush();
        telemetry.flush();
        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        assertEquals("matter.classified", cap.getValue().getEventName());
        return cap.getValue().getAttrs();
    }

    @Test
    void validLabelIsRecordedAndRawMessageNeverStored() throws Exception {
        when(model.generate(anyString())).thenReturn("争议解决");
        svc.classifyAsync("conv-1", "对方拖欠货款起诉需要准备什么材料", false);
        String attrs = capturedCategory();
        assertTrue(attrs.contains("争议解决"));
        assertTrue(attrs.contains("\"source\":\"ai\""));
        assertFalse(attrs.contains("拖欠货款"));
    }

    @Test
    void nonEnumOutputFallsBackToOtherLegal() throws Exception {
        when(model.generate(anyString())).thenReturn("这看起来像是一个关于诉讼的问题");
        svc.classifyAsync("conv-2", "帮我看看这个案子", false);
        assertTrue(capturedCategory().contains("其他法律事务"));
    }

    @Test
    void skillMatchedSkipsLlmEntirely() throws Exception {
        svc.classifyAsync("conv-3", "股东大会核查", true);
        svc.flush();
        verify(factory, never()).getChatModel(anyString());
        verify(repo, never()).save(any());
    }

    @Test
    void disabledRollupSwitchSkipsClassification() throws Exception {
        when(settingStore.get(eq(TelemetrySettings.KEY_ROLLUP), anyString())).thenReturn("false");
        svc.classifyAsync("conv-4", "帮我审合同", false);
        svc.flush();
        verify(factory, never()).getChatModel(anyString());
    }

    @Test
    void sameConversationClassifiedOnlyOnce() throws Exception {
        when(model.generate(anyString())).thenReturn("合同审查起草");
        svc.classifyAsync("conv-5", "帮我审合同", false);
        svc.classifyAsync("conv-5", "帮我审合同", false);
        svc.flush();
        telemetry.flush();
        verify(model, times(1)).generate(anyString());
    }

    @Test
    void llmFailureIsSwallowed() {
        when(model.generate(anyString())).thenThrow(new RuntimeException("model down"));
        assertDoesNotThrow(() -> {
            svc.classifyAsync("conv-6", "帮我审合同", false);
            svc.flush();
        });
    }
}
