package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.model.ai.AiAssistantConfig;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AiAssistantService 测试：助手配置动态加载与 ProjectAssistant 实例缓存
 * （原 AiChatController.loadAssistants/getAssistant 下沉后的专职服务）
 */
class AiAssistantServiceTest {

    private SystemSettingService systemSettingService;
    private ChatModelFactory chatModelFactory;
    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        systemSettingService = mock(SystemSettingService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getChatModel(any())).thenReturn(mock(ChatLanguageModel.class));

        service = new AiAssistantService(systemSettingService, new AiModelProperties(),
                chatModelFactory, new ObjectMapper());
    }

    @Test
    @DisplayName("loadAssistants：从系统设置解析 JSON 并保留顺序")
    void loadsAssistantsFromSystemSettings() {
        when(systemSettingService.get(eq("ai.assistants"), any())).thenReturn(
                "[{\"id\":\"default\",\"name\":\"默认\"},{\"id\":\"legal\",\"name\":\"法律\"}]");

        Map<String, AiAssistantConfig> assistants = service.loadAssistants();

        assertEquals(2, assistants.size());
        assertEquals("默认", assistants.get("default").getName());
        assertEquals(java.util.List.of("default", "legal"),
                new java.util.ArrayList<>(assistants.keySet()), "应保留配置顺序");
    }

    @Test
    @DisplayName("loadAssistants：配置为空或非法 JSON 时返回空表")
    void handlesMissingOrInvalidConfig() {
        when(systemSettingService.get(eq("ai.assistants"), any())).thenReturn(null);
        assertTrue(service.loadAssistants().isEmpty());

        when(systemSettingService.get(eq("ai.assistants"), any())).thenReturn("not-json");
        assertTrue(service.loadAssistants().isEmpty());
    }

    @Test
    @DisplayName("getAssistant：按 模型+助手 缓存实例")
    void cachesAssistantByModelAndAssistantId() {
        ProjectAssistant first = service.getAssistant("model-a", null);
        ProjectAssistant again = service.getAssistant("model-a", null);
        assertSame(first, again, "同一模型+助手应复用缓存实例");

        ProjectAssistant other = service.getAssistant("model-b", null);
        assertNotSame(first, other, "不同模型应创建不同实例");

        verify(chatModelFactory, times(2)).getChatModel(any());
    }
}
