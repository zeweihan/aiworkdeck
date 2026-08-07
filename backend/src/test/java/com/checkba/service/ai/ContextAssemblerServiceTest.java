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
    private ClientCapabilityService capabilityService;
    private InlineContentCache inlineContentCache;

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

        capabilityService = new ClientCapabilityService();
        inlineContentCache = new InlineContentCache();
        assembler = new ContextAssemblerService(
                legalTools, messageService, fileContextLoader,
                new AiContextProperties(), skillRouter, capabilityService, inlineContentCache,
                memoryManager, contextCompressor);
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
                props, mockedSkillRouter(), new ClientCapabilityService(), new InlineContentCache(),
                mockedMemoryManager(), mockedCompressor());

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

    // ==== 正文省传（inlineContentHash + InlineContentCache）====
    // 同一会话里文档没变时，插件只上送内容哈希，不再重传整篇正文（20 万字符上限）。

    private static AiAgentController.ContextItem officeDocByHash(String hash) {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId("office-current-document");
        item.setName("劳动合同.docx");
        item.setFileType("docx");
        item.setInlineContentHash(hash);
        return item;
    }

    @Test
    @DisplayName("带正文的请求：正常注入，并按会话落入内联正文缓存")
    void inlineContentIsCachedForLaterHashOnlyRequests() {
        String body = "第一条 试用期为三个月……";

        String systemText = assembleSystemText(officeDoc(body));

        assertTrue(systemText.contains(body), "本轮正文应正常注入");
        assertEquals(body, inlineContentCache.get("conv-1", InlineContentCache.sha256Hex(body)),
                "正文应按会话落缓存，哈希由后端自算");
    }

    @Test
    @DisplayName("只带哈希且命中缓存：复用上一轮正文，不回落 read_document")
    void hashOnlyRequestReusesCachedContent() {
        String body = "第一条 试用期为三个月……";
        assembleSystemText(officeDoc(body));

        String systemText = assembleSystemText(officeDocByHash(InlineContentCache.sha256Hex(body)));

        assertTrue(systemText.contains(body), "命中缓存应复用上一轮正文");
        org.mockito.Mockito.verify(legalTools, org.mockito.Mockito.never()).read_document(anyString());
    }

    @Test
    @DisplayName("只带哈希但未命中（文档已改/缓存已驱逐）：降级为无正文，不报错也不回落 read_document")
    void hashMissDegradesToNoInlineBody() {
        capabilityService.record("conv-1", "office");
        String body = "第一条 试用期为三个月……";
        assembleSystemText(officeDoc(body));

        String systemText = assembleSystemText(officeDocByHash(InlineContentCache.sha256Hex("文档已被改动")));

        assertFalse(systemText.contains(body), "哈希对不上不应复用旧正文");
        assertTrue(systemText.contains("[正文暂不可读，可用 office_get_text 直接读取]"),
                "未命中应走既有「正文暂不可读」文案，模型改用读取类工具");
        org.mockito.Mockito.verify(legalTools, org.mockito.Mockito.never()).read_document(anyString());
    }

    // ==== 末位提醒按会话客户端能力切换（Phase C）====
    // office 会话的编辑工具是 office_*，提醒里再点名 doc_* 就是把模型往死路径上引。

    @Test
    @DisplayName("office 会话：末位提醒改用 office_* 口径，不再点名 doc_* 工具")
    void reminderSwitchesToOfficeWordingForOfficeCapability() {
        capabilityService.record("conv-1", "office");

        String lastUser = assembleLastUserText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(lastUser.contains("[系统提醒]"), "office 会话也应有末位提醒");
        assertTrue(lastUser.contains("office_replace_text"), "应指引用 office_* 工具修改文档");
        assertTrue(lastUser.contains("修订"), "应说明修改以 Word 原生修订呈现");
        // 排版能力不点名，模型就不知道自己能改格式（维护者反馈第 8 条的根因之一）
        assertTrue(lastUser.contains("office_format_text"), "应点名字符格式工具");
        assertTrue(lastUser.contains("office_set_paragraph_format"), "应点名段落格式工具");
        assertTrue(lastUser.contains("office_apply_standard_format"), "应点名整篇标准格式化工具");
        assertFalse(lastUser.contains("doc_list_project_files"), "office 会话不应再点名 doc_* 工具");
        assertFalse(lastUser.contains("doc_open_file"), "office 会话不应再点名 doc_* 工具");
    }

    @Test
    @DisplayName("office 会话：system prompt 活跃文档段同样切换为 office_* 口径")
    void systemPromptActiveDocSectionSwitchesForOfficeCapability() {
        capabilityService.record("conv-1", "office");

        String systemText = assembleSystemText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(systemText.contains("office_get_text"), "应指引用 office_* 工具读取");
        assertTrue(systemText.contains("office_set_paragraph_format"), "活跃文档段应点名排版工具");
        assertTrue(systemText.contains("office_set_numbering"), "活跃文档段应点名自动编号工具");
        // 基底 system_prompt.md 里仍有 doc_* 工具表（会话工具过滤才是硬闸门），
        // 这里只断言活跃文档段自身的 LOWA 口径语句没有出现
        assertFalse(systemText.contains("**无需也不要**调用"), "活跃文档段不应再是 doc_* 口径");
    }

    @Test
    @DisplayName("office+excel 会话：提醒与活跃文档段改用 office_excel_* 口径，不点名 Word 面工具")
    void wordingSwitchesToExcelToolsForExcelHost() {
        capabilityService.record("conv-1", "office", "excel");

        String lastUser = assembleLastUserText(officeDoc("名称\t金额\n甲\t100"));
        assertTrue(lastUser.contains("[系统提醒]"), "excel 会话也应有末位提醒");
        assertTrue(lastUser.contains("office_excel_set_values"), "应指引用 office_excel_* 工具修改");
        assertFalse(lastUser.contains("office_replace_text"), "excel 会话不应点名 Word 面 office_* 工具");
        assertFalse(lastUser.contains("doc_list_project_files"), "excel 会话不应点名 doc_* 工具");

        String systemText = assembleSystemText(officeDoc("名称\t金额\n甲\t100"));
        assertTrue(systemText.contains("office_excel_get_range"), "活跃文档段应指引 office_excel_* 读取");
        assertFalse(systemText.contains("office_get_text"), "活跃文档段不应再点名 Word 面工具");
    }

    @Test
    @DisplayName("office+powerpoint 会话：提醒与活跃文档段改用 office_ppt_* 口径")
    void wordingSwitchesToPptToolsForPowerpointHost() {
        capabilityService.record("conv-1", "office", "powerpoint");

        String lastUser = assembleLastUserText(officeDoc("第1页：项目介绍……"));
        assertTrue(lastUser.contains("office_ppt_replace_text"), "应指引用 office_ppt_* 工具修改");
        assertFalse(lastUser.contains("office_insert_text"), "ppt 会话不应点名 Word 面 office_* 工具");

        String systemText = assembleSystemText(officeDoc("第1页：项目介绍……"));
        assertTrue(systemText.contains("office_ppt_get_slides"), "活跃文档段应指引 office_ppt_* 读取");
    }

    @Test
    @DisplayName("office 会话未上送宿主：按 Word 兜底，保持既有 office_* 口径")
    void officeCapabilityWithoutHostDefaultsToWordWording() {
        capabilityService.record("conv-1", "office");

        String lastUser = assembleLastUserText(officeDoc("第一条 试用期为三个月……"));
        assertTrue(lastUser.contains("office_replace_text"), "缺省宿主应保持 Word 面口径");
        assertFalse(lastUser.contains("office_excel_"), "缺省宿主不应出现 Excel 面口径");
    }

    @Test
    @DisplayName("none 会话：末位提醒为只读口径，不点名任何编辑工具")
    void reminderReadOnlyForNoneCapability() {
        capabilityService.record("conv-1", "none");

        String lastUser = assembleLastUserText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(lastUser.contains("[系统提醒]"), "none 会话也应有末位提醒");
        assertTrue(lastUser.contains("仅供阅读"), "应说明只读语义");
        assertFalse(lastUser.contains("office_"), "none 会话不应点名 office_* 工具");
        assertFalse(lastUser.contains("doc_list_project_files"), "none 会话不应点名 doc_* 工具");
    }

    @Test
    @DisplayName("默认（未声明能力）会话：保持现状 doc_* 口径")
    void reminderKeepsLowaWordingByDefault() {
        String lastUser = assembleLastUserText(officeDoc("第一条 试用期为三个月……"));

        assertTrue(lastUser.contains("doc_list_project_files"), "默认能力应保持 doc_* 口径");
        assertFalse(lastUser.contains("office_replace_text"), "默认能力不应出现 office_* 口径");
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
