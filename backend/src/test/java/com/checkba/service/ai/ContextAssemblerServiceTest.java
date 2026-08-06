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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private List<ChatMessage> assembleMessages(AiAgentController.ContextItem activeContext) {
        return assembler.assemble(
                "conv-1", "帮我修订一下", null, activeContext,
                null, null, "88", AgentMode.AGENT, 1L, null);
    }

    private String assembleSystemText(AiAgentController.ContextItem activeContext) {
        return ((SystemMessage) assembleMessages(activeContext).get(0)).text();
    }

    /** 末位消息（用户消息）的文本——注意力最高的位置。 */
    private String assembleLastUserText(AiAgentController.ContextItem activeContext) {
        List<ChatMessage> messages = assembleMessages(activeContext);
        return ((dev.langchain4j.data.message.UserMessage) messages.get(messages.size() - 1)).singleText();
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

    // ==== 末位提醒 ====
    // 真机日志实证：system prompt 里的活跃文档声明（连正文一起注入）会被弱模型稳定无视，
    // 注入后 6 秒仍调 doc_list_project_files 重新发现文档。末位消息是注意力最高的位置。

    @Test
    @DisplayName("活跃文档提醒挂在用户消息尾部，且点名禁用 list/open 两个工具")
    void activeDocumentReminderRidesOnLastUserMessage() {
        when(legalTools.read_document("123")).thenReturn("第一条 合作范围……");

        String lastUser = assembleLastUserText(activeDoc());

        assertTrue(lastUser.startsWith("帮我修订一下"), "用户原话必须在前，提醒只作为尾部追加");
        assertTrue(lastUser.contains("[系统提醒]"), "应沿用既有系统提醒惯例");
        assertTrue(lastUser.contains("合作框架协议.docx"), "提醒里应点名当前文档");
        assertTrue(lastUser.contains("doc_list_project_files"), "应点名禁用 doc_list_project_files");
        assertTrue(lastUser.contains("doc_open_file"), "应点名禁用 doc_open_file");
    }

    @Test
    @DisplayName("正文读取失败也要挂末位提醒——模型至少知道该操作哪个文档")
    void reminderPresentEvenWhenContentUnreadable() {
        when(legalTools.read_document("123")).thenReturn(null);

        String lastUser = assembleLastUserText(activeDoc());

        assertTrue(lastUser.contains("合作框架协议.docx"), "正文读不到也应保留提醒");
    }

    @Test
    @DisplayName("无活跃文档时用户消息保持原样，不夹带提醒")
    void noReminderWhenNoActiveContext() {
        assertEquals("帮我修订一下", assembleLastUserText(null), "无活跃文档时用户消息不应被改写");
    }

    // ==== 内联正文（inlineContent，Office 插件路径）====
    // Office 插件里的文档在客户端本地，后端没有可读的 fileId——正文随 /chat 请求内联携带。

    private static AiAgentController.ContextItem officeDoc(String inlineContent) {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId("office-current-document");
        item.setName("劳动合同.docx");
        item.setFileType("docx");
        item.setInlineContent(inlineContent);
        return item;
    }

    @Test
    @DisplayName("内联正文优先：直接注入请求携带的正文，不再调 read_document")
    void inlineContentPreferredOverReadDocument() {
        String systemText = assembleSystemText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(systemText.contains("<active_document id=\"office-current-document\""),
                "应注入 active_document 标签");
        assertTrue(systemText.contains("第一条 试用期为三个月……"), "正文应来自内联内容");
        org.mockito.Mockito.verify(legalTools, org.mockito.Mockito.never()).read_document(anyString());
    }

    @Test
    @DisplayName("内联正文为空串时回退到 read_document(fileId) 既有路径")
    void emptyInlineContentFallsBackToReadDocument() {
        when(legalTools.read_document("office-current-document")).thenReturn("后端读到的正文");

        String systemText = assembleSystemText(officeDoc(""));

        assertTrue(systemText.contains("后端读到的正文"), "空内联内容应走既有 read_document 路径");
    }

    @Test
    @DisplayName("内联正文超过 200k 字符时截断，防滥用")
    void oversizedInlineContentIsTruncated() {
        // 抬高下游 maxCharsPerFile，隔离出内联上限自身的截断行为
        AiContextProperties props = new AiContextProperties();
        props.getFiles().setMaxCharsPerFile(500_000);
        ContextAssemblerService bigLimitAssembler = new ContextAssemblerService(
                legalTools, mockedMessageService(), mock(FileContextLoader.class),
                props, mockedSkillRouter(), mockedMemoryManager(), mockedCompressor());

        String huge = "甲".repeat(200_001);
        List<ChatMessage> messages = bigLimitAssembler.assemble(
                "conv-1", "帮我修订一下", null, officeDoc(huge),
                null, null, "88", AgentMode.AGENT, 1L, null);
        String systemText = ((SystemMessage) messages.get(0)).text();

        assertTrue(systemText.contains("[TRUNCATED - Inline content too long]"), "超限应带截断标记");
        assertFalse(systemText.contains("甲".repeat(200_001)), "不应完整注入超限正文");
    }

    @Test
    @DisplayName("内联正文场景下，末位提醒逻辑保持不变（仍点名文档并禁用 list/open）")
    void reminderUnchangedForInlineContent() {
        String lastUser = assembleLastUserText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(lastUser.startsWith("帮我修订一下"), "用户原话必须在前");
        assertTrue(lastUser.contains("[系统提醒]"), "内联正文路径也应有末位提醒");
        assertTrue(lastUser.contains("劳动合同.docx"), "提醒里应点名当前文档");
        assertTrue(lastUser.contains("doc_list_project_files"), "应点名禁用 doc_list_project_files");
    }

    // ---- 供自建 assembler 的 mock 工厂（与 setUp 同配方）----

    private static ProjectAiMessageService mockedMessageService() {
        ProjectAiMessageService svc = mock(ProjectAiMessageService.class);
        when(svc.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        return svc;
    }

    private static SkillRouter mockedSkillRouter() {
        SkillRouter router = mock(SkillRouter.class);
        when(router.match(anyString())).thenReturn(Optional.empty());
        return router;
    }

    private static MemoryManager mockedMemoryManager() {
        MemoryManager mm = mock(MemoryManager.class);
        when(mm.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(mm.retrieveMemories(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(mm.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        return mm;
    }

    private static ContextCompressor mockedCompressor() {
        ContextCompressor cc = mock(ContextCompressor.class);
        when(cc.needsCompression(any(), any())).thenReturn(false);
        return cc;
    }
}
