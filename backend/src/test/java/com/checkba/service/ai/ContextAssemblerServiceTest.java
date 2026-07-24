package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContextAssemblerService 的活跃文档（activeContext）注入行为测试。
 *
 * 背景：用户在编辑器里开着某个文档时说"帮我修订一下"，模型应直接编辑该文档，
 * 而不是先 doc_list_project_files / doc_open_file 去重新发现文档。
 */
class ContextAssemblerServiceTest {

    private LegalTools legalTools;
    private ContextAssemblerService assembler;

    @BeforeEach
    void setUp() {
        legalTools = mock(LegalTools.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        FileContextLoader fileContextLoader = mock(FileContextLoader.class);
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        when(contextCompressor.needsCompression(any(), any())).thenReturn(false);

        assembler = new ContextAssemblerService(
                legalTools, messageService, fileContextLoader,
                new AiContextProperties(), skillRouter, memoryManager, contextCompressor);
    }

    private String assembleSystemText(AiAgentController.ContextItem activeContext) {
        List<ChatMessage> messages = assembler.assemble(
                "conv-1", "帮我修订一下", null, activeContext,
                null, null, "88", AgentMode.AGENT, 1L, null);
        return ((SystemMessage) messages.get(0)).text();
    }

    private static AiAgentController.ContextItem activeDoc() {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId("123");
        item.setName("合作框架协议.docx");
        item.setFileType("docx");
        return item;
    }

    @Test
    @DisplayName("活跃文档注入：声明文档已在编辑器打开，直接编辑，无需再列出/打开")
    void activeContextInjectionTellsModelDocIsAlreadyOpen() {
        when(legalTools.read_document("123")).thenReturn("第一条 合作范围……");

        String systemText = assembleSystemText(activeDoc());

        assertTrue(systemText.contains("<active_document id=\"123\""), "应注入 active_document 标签");
        assertTrue(systemText.contains("已在编辑器中打开"), "应声明该文档已打开");
        assertTrue(systemText.contains("doc_list_project_files"),
                "应明确点名无需 doc_list_project_files");
        assertTrue(systemText.contains("doc_open_file"),
                "应明确点名无需 doc_open_file");
    }

    @Test
    @DisplayName("活跃文档正文读取失败时，仍注入文档标识（id/name），不整块静默丢弃")
    void activeContextStillInjectedWhenContentUnreadable() {
        when(legalTools.read_document("123")).thenReturn(null);

        String systemText = assembleSystemText(activeDoc());

        assertTrue(systemText.contains("合作框架协议.docx"), "正文读不到也应保留文档名");
        assertTrue(systemText.contains("已在编辑器中打开"), "正文读不到也应声明该文档已打开");
    }

    @Test
    @DisplayName("无活跃文档时不注入 active_document 段")
    void noActiveContextNoInjection() {
        String systemText = assembleSystemText(null);

        assertFalse(systemText.contains("<active_document id="), "无活跃文档不应出现注入段");
        assertFalse(systemText.contains("# Active Document"), "无活跃文档不应出现注入段标题");
    }
}
