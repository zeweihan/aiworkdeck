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

    // ==== 修复：classifyAsync 只在会话首轮被调用，去重位提前置上后就再没有第二次机会——
    // 真正值得做的是给 AI 调用本身加有限次重试，以及给去重集合加上界（见类注释与 PR 说明）。

    @Test
    void transientFailureIsRetriedAndSucceedsOnSecondAttempt() throws Exception {
        // 第一次调用（网络抖动/瞬时 5xx 等）失败，第二次成功——不该像原来那样一次失败就永久放弃。
        when(model.generate(anyString()))
                .thenThrow(new RuntimeException("model down"))
                .thenReturn("合规监管");
        svc.classifyAsync("conv-7", "帮我看看合规要求", false);
        String attrs = capturedCategory();
        assertTrue(attrs.contains("合规监管"));
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void retriesGiveUpAfterBoundedAttempts() throws Exception {
        when(model.generate(anyString())).thenThrow(new RuntimeException("model down"));
        assertDoesNotThrow(() -> {
            svc.classifyAsync("conv-8", "帮我看看合规要求", false);
            svc.flush();
        });
        // 重试次数必须有限——不能因为一直失败就无限重试拖住后台线程。
        verify(model, times(svc.maxClassifyAttempts)).generate(anyString());
        verify(repo, never()).save(any());
    }

    @Test
    void classifiedSetHasUpperBoundAndResetsInsteadOfGrowingForever() throws Exception {
        when(model.generate(anyString())).thenReturn("其他法律事务");
        svc.maxClassifiedEntries = 2; // 缩小上界，测试不必真跑一万次

        svc.classifyAsync("conv-a", "问题一", false);
        svc.classifyAsync("conv-b", "问题二", false);
        svc.flush();
        // 去重集合已达上界（2），第三个不同会话到来时必须整体清空重来，
        // 而不是无限增长——即便这意味着 conv-a 会被重复分类一次。
        svc.classifyAsync("conv-a", "问题一，再来一次", false);
        svc.flush();

        verify(model, times(3)).generate(anyString());
    }
}
