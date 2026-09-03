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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    private com.checkba.service.AppLanguageService appLanguageService;
    private ChatModelFactory chatModelFactory;
    private com.checkba.service.ProjectFileService projectFileService;

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
        // 应用语言：mock 默认 isEnglish()=false，即 zh-CN——既有断言全部走中文路径（行为保持）
        appLanguageService = mock(com.checkba.service.AppLanguageService.class);
        // 视觉能力默认关：既有断言全部走「模型不支持视觉」这条既有行为路径，行为保持
        chatModelFactory = mockedChatModelFactory();
        projectFileService = mock(com.checkba.service.ProjectFileService.class);
        assembler = new ContextAssemblerService(
                legalTools, messageService, fileContextLoader,
                new AiContextProperties(), skillRouter, capabilityService, inlineContentCache,
                memoryManager, contextCompressor, appLanguageService,
                chatModelFactory, projectFileService);
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
        // 不能用 singleText()：本轮消息带图片时它会抛异常，测试挂掉的形态会长得像
        // 「改坏了别的东西」而不是「断言失败」。取文本一律走同一个口径。
        return com.checkba.service.ai.context.ChatMessageText.of(messages.get(messages.size() - 1));
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
                mockedMemoryManager(), mockedCompressor(),
                mock(com.checkba.service.AppLanguageService.class),
                mockedChatModelFactory(), mock(com.checkba.service.ProjectFileService.class));

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

    // ==== Skill 注入（手动选择 / 自动命中）====
    // 守的是"工具裁剪与 prompt 注入必须同源"：这里改成读 SkillRouter 登记的生效集合之前，
    // 组装器是自己重新 match(userPrompt) 的，于是用户手动选的 skill 被裁了工具却拿不到 prompt。

    /** 建一个带真实 SkillRouter（temp skills 目录）的组装器，返回 [assembler, router] */
    private Object[] assemblerWithRealSkills(java.nio.file.Path skillsDir) throws java.io.IOException {
        java.nio.file.Path dir = skillsDir.resolve("manual-skill");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("skill.yml"),
                "id: manual-skill\nname: 手动技能\ntriggers: [绝不会出现的触发词]\nallowed_tools: [law_search]\n");
        java.nio.file.Files.writeString(dir.resolve("prompt.md"), "手动技能模板正文MANUAL");

        java.nio.file.Path autoDir = skillsDir.resolve("auto-skill");
        java.nio.file.Files.createDirectories(autoDir);
        java.nio.file.Files.writeString(autoDir.resolve("skill.yml"),
                "id: auto-skill\nname: 自动技能\ntriggers: [修订]\nallowed_tools: [law_search]\n");
        java.nio.file.Files.writeString(autoDir.resolve("prompt.md"), "自动技能模板正文AUTO");

        com.checkba.service.ai.skill.SkillProperties skillProps =
                new com.checkba.service.ai.skill.SkillProperties();
        skillProps.setDir(skillsDir.toString());
        com.checkba.service.ai.skill.SkillRegistry registry =
                new com.checkba.service.ai.skill.SkillRegistry(
                        skillProps, null, new com.checkba.service.ai.PluginService(), null);
        registry.init();
        SkillRouter realRouter = new SkillRouter(registry, skillProps,
                mock(com.checkba.service.telemetry.TelemetryService.class), null);
        ContextAssemblerService realAssembler = new ContextAssemblerService(
                legalTools, mockedMessageService(), mock(FileContextLoader.class),
                new AiContextProperties(), realRouter, new ClientCapabilityService(),
                new InlineContentCache(), mockedMemoryManager(), mockedCompressor(),
                mock(com.checkba.service.AppLanguageService.class),
                mockedChatModelFactory(), mock(com.checkba.service.ProjectFileService.class));
        return new Object[]{realAssembler, realRouter};
    }

    @Test
    @DisplayName("手动选择的 skill 必须注入 prompt（不能只裁工具——旧 pinnedSkillId 的静默 bug）")
    void manuallySelectedSkillIsInjectedIntoPrompt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        Object[] parts = assemblerWithRealSkills(tmp);
        ContextAssemblerService realAssembler = (ContextAssemblerService) parts[0];
        SkillRouter realRouter = (SkillRouter) parts[1];

        // 用户这句话不含 manual-skill 的任何触发词，纯靠手动勾选
        realRouter.activateForTurn("conv-skill", "帮我看看这个", null, List.of("manual-skill"));
        String systemText = ((SystemMessage) realAssembler.assemble(
                "conv-skill", "帮我看看这个", null, null, null, null, "88",
                AgentMode.AGENT, 1L, null).get(0)).text();

        assertTrue(systemText.contains("手动技能模板正文MANUAL"),
                "手动选择的 skill 必须真的注入 prompt");
    }

    @Test
    @DisplayName("手动 + 自动同时生效：两段 prompt 都注入")
    void manualAndAutoSkillsBothInjected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        Object[] parts = assemblerWithRealSkills(tmp);
        ContextAssemblerService realAssembler = (ContextAssemblerService) parts[0];
        SkillRouter realRouter = (SkillRouter) parts[1];

        // "帮我修订一下" 命中 auto-skill 的触发词「修订」，同时手动勾了 manual-skill
        realRouter.activateForTurn("conv-both", "帮我修订一下", null, List.of("manual-skill"));
        String systemText = ((SystemMessage) realAssembler.assemble(
                "conv-both", "帮我修订一下", null, null, null, null, "88",
                AgentMode.AGENT, 1L, null).get(0)).text();

        assertTrue(systemText.contains("手动技能模板正文MANUAL"), "手动选择的 skill 应注入");
        assertTrue(systemText.contains("自动技能模板正文AUTO"), "自动命中的 skill 也应注入");
    }

    @Test
    @DisplayName("ASK 模式跳过 skill 注入（含手动选择——该模式本来就不传工具）")
    void askModeSkipsSkillInjection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        Object[] parts = assemblerWithRealSkills(tmp);
        ContextAssemblerService realAssembler = (ContextAssemblerService) parts[0];
        SkillRouter realRouter = (SkillRouter) parts[1];

        realRouter.activateForTurn("conv-ask", "帮我修订一下", null, List.of("manual-skill"));
        String systemText = ((SystemMessage) realAssembler.assemble(
                "conv-ask", "帮我修订一下", null, null, null, null, "88",
                AgentMode.ASK, 1L, null).get(0)).text();

        assertFalse(systemText.contains("手动技能模板正文MANUAL"));
        assertFalse(systemText.contains("自动技能模板正文AUTO"));
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
    @DisplayName("office+word 会话：多处修改必须成批（office_replace_batch），且只对 Word 宿主说（dev-board#419）")
    void wordSessionIsToldToBatchMultiEdits() {
        capabilityService.record("conv-1", "office", "word");

        String systemText = assembleSystemText(officeDoc("第一条 甲方应承担违约责仁……"));
        assertTrue(systemText.contains("office_replace_batch"), "Word 面活跃文档段应点名批量改写工具");
        assertTrue(systemText.contains("不要逐处调用 office_replace_text"),
                "必须明说逐处调用会撞上步数上限——这正是「正在操作文档卡住」的成因");
        assertTrue(systemText.contains("绝不要整批重发"), "必须明说失败条目单独重试，否则成功的会被改第二遍");

        // Excel / PPT 宿主没有这个工具，不能对它们说
        capabilityService.record("conv-1", "office", "excel");
        assertFalse(assembleSystemText(officeDoc("名称\t金额")).contains("office_replace_batch"),
                "excel 会话不应点名 Word 面的批量改写工具");
        capabilityService.record("conv-1", "office", "powerpoint");
        assertFalse(assembleSystemText(officeDoc("第1页：项目介绍")).contains("office_replace_batch"),
                "powerpoint 会话不应点名 Word 面的批量改写工具");
    }

    @Test
    @DisplayName("office+word 会话：整篇任务被要求走分段过卷 office_pass_step（dev-board#422）")
    void wordSessionIsToldToUsePassStepForWholeDocumentWork() {
        capabilityService.record("conv-1", "office", "word");

        String systemText = assembleSystemText(officeDoc("第一条 甲方应承担违约责仁……"));
        assertTrue(systemText.contains("office_pass_step"), "Word 面应点名分段过卷工具");
        assertTrue(systemText.indexOf("必须用 office_pass_step 分块推进") > systemText.indexOf("绝不要整批重发"),
                "过卷指引挂在 #419 那段之后（约束要挂末位）");

        // Excel / PPT 宿主没有这个工具，不能对它们说
        capabilityService.record("conv-1", "office", "excel");
        assertFalse(assembleSystemText(officeDoc("名称\t金额")).contains("office_pass_step"),
                "excel 会话不应点名 Word 面的过卷工具");
        capabilityService.record("conv-1", "office", "powerpoint");
        assertFalse(assembleSystemText(officeDoc("第1页：项目介绍")).contains("office_pass_step"),
                "powerpoint 会话不应点名 Word 面的过卷工具");
    }

    @Test
    @DisplayName("office+excel 会话：提醒与活跃文档段改用 office_excel_* 口径，不点名 Word 面工具")
    void wordingSwitchesToExcelToolsForExcelHost() {
        capabilityService.record("conv-1", "office", "excel");

        String lastUser = assembleLastUserText(officeDoc("名称\t金额\n甲\t100"));
        assertTrue(lastUser.contains("[系统提醒]"), "excel 会话也应有末位提醒");
        assertTrue(lastUser.contains("office_excel_set_values"), "应指引用 office_excel_* 工具修改");
        assertTrue(lastUser.contains("office_excel_format_cells"), "末位提醒应点名格式/结构工具集（批次6）");
        assertTrue(lastUser.contains("office_excel_manage_sheets"), "末位提醒应点名工作表管理工具（批次6）");
        assertTrue(lastUser.contains("office_excel_set_autofilter"), "末位提醒应点名自动筛选工具（批次6追加）");
        assertTrue(lastUser.contains("office_excel_conditional_format"), "末位提醒应点名条件格式工具（批次6追加）");
        assertFalse(lastUser.contains("office_replace_text"), "excel 会话不应点名 Word 面 office_* 工具");
        assertFalse(lastUser.contains("doc_list_project_files"), "excel 会话不应点名 doc_* 工具");

        String systemText = assembleSystemText(officeDoc("名称\t金额\n甲\t100"));
        assertTrue(systemText.contains("office_excel_get_range"), "活跃文档段应指引 office_excel_* 读取");
        assertTrue(systemText.contains("office_excel_set_formulas"), "活跃文档段应点名公式工具（批次6）");
        assertTrue(systemText.contains("office_excel_get_overview"), "活跃文档段应点名总览工具（批次6追加）");
        assertFalse(systemText.contains("office_get_text"), "活跃文档段不应再点名 Word 面工具");
    }

    @Test
    @DisplayName("office+powerpoint 会话：提醒与活跃文档段改用 office_ppt_* 口径")
    void wordingSwitchesToPptToolsForPowerpointHost() {
        capabilityService.record("conv-1", "office", "powerpoint");

        String lastUser = assembleLastUserText(officeDoc("第1页：项目介绍……"));
        assertTrue(lastUser.contains("office_ppt_replace_text"), "应指引用 office_ppt_* 工具修改");
        assertTrue(lastUser.contains("office_ppt_add_slide"), "应点名新增幻灯片工具（批次7）");
        assertTrue(lastUser.contains("office_ppt_format_text"), "应点名幻灯片文字排版工具（批次7）");
        assertFalse(lastUser.contains("office_insert_text"), "ppt 会话不应点名 Word 面 office_* 工具");

        String systemText = assembleSystemText(officeDoc("第1页：项目介绍……"));
        assertTrue(systemText.contains("office_ppt_get_slides"), "活跃文档段应指引 office_ppt_* 读取");
        assertTrue(systemText.contains("office_ppt_delete_shape"), "活跃文档段应点名精确删除形状工具（批次7）");
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

    // ==== 应用语言切换（EN 版 PR5）====
    // en-US 时选英文 system prompt / enforcement / 模式约束 / 活跃文档指引与末位提醒；
    // zh-CN（本测试类其余全部用例）行为与引入前逐字节一致。

    @Test
    @DisplayName("英文模式：system prompt 为英文，Language 行是 ENGLISH ONLY，不含中文约束字样")
    void englishModeAssemblesEnglishSystemPrompt() {
        when(appLanguageService.isEnglish()).thenReturn(true);
        when(legalTools.read_document("123")).thenReturn("Article 1 Scope of Cooperation...");

        String systemText = assembleSystemText(activeDoc());

        assertTrue(systemText.contains("ENGLISH ONLY"), "enforcement 段的 Language 行应为 ENGLISH ONLY");
        assertFalse(systemText.contains("SIMPLIFIED CHINESE ONLY"), "不应再出现中文版 Language 行");
        assertFalse(systemText.contains("Simplified Chinese"), "基底 prompt 不应再要求简体中文输出");
        assertFalse(systemText.contains("简体中文"), "英文模式下不应出现「简体中文」类字样");
        assertTrue(systemText.contains("jurisdiction-neutral"), "应加载英文版基底 system_prompt.en.md");
        assertTrue(systemText.contains("# MODE: AGENT MODE (autonomous execution)"),
                "模式约束应为英文版");
        assertFalse(systemText.contains("自动执行模式"), "不应再出现中文版模式约束");
    }

    @Test
    @DisplayName("英文模式：活跃文档指引与末位提醒为英文，协议要点（禁 list/open）保持")
    void englishModeActiveDocGuidanceAndReminderInEnglish() {
        when(appLanguageService.isEnglish()).thenReturn(true);
        when(legalTools.read_document("123")).thenReturn("Article 1 Scope of Cooperation...");

        String systemText = assembleSystemText(activeDoc());
        assertTrue(systemText.contains("# Active Document"), "应有英文活跃文档段标题");
        assertTrue(systemText.contains("is open in the editor"), "应声明文档已打开（英文）");
        assertFalse(systemText.contains("已在编辑器中打开"), "不应再出现中文声明");
        assertTrue(systemText.contains("<active_document id=\"123\""), "active_document 标签结构不变");

        String lastUser = assembleLastUserText(activeDoc());
        assertTrue(lastUser.startsWith("帮我修订一下"), "用户原话仍在前，提醒只作尾部追加");
        assertTrue(lastUser.contains("[System reminder]"), "末位提醒应为英文口径");
        assertFalse(lastUser.contains("[系统提醒]"), "不应再出现中文提醒前缀");
        assertTrue(lastUser.contains("doc_list_project_files"), "英文提醒仍须点名禁用 doc_list_project_files");
        assertTrue(lastUser.contains("doc_open_file"), "英文提醒仍须点名禁用 doc_open_file");
    }

    @Test
    @DisplayName("中文模式（默认）：enforcement 的 Language 行保持 SIMPLIFIED CHINESE ONLY")
    void chineseModeKeepsChineseLanguageRule() {
        when(legalTools.read_document("123")).thenReturn("第一条 合作范围……");

        String systemText = assembleSystemText(activeDoc());

        assertTrue(systemText.contains("SIMPLIFIED CHINESE ONLY"), "中文模式 Language 行不变");
        assertFalse(systemText.contains("ENGLISH ONLY"), "中文模式不应出现英文 Language 行");
    }

    // ==== 历史回放容错：一条空白 content 的历史消息不许掀翻整轮 assemble ====
    // 背景：POST /chat 此前没挡住 message="" 落库；ContextAssemblerService 回放历史时
    // langchain4j 的 UserMessage.from(text) 对空白文本一律抛 IllegalArgumentException，
    // 存量脏数据一旦落库，该 conversationId 此后每一轮都会在这里抛异常、永久报废。

    @Test
    @DisplayName("修复：历史里一条空白 content 的消息被跳过，不再让整轮 assemble 抛异常")
    void blankHistoryMessageIsSkippedNotThrown() {
        com.checkba.model.entity.ProjectAiMessage blank = new com.checkba.model.entity.ProjectAiMessage();
        blank.setId(1L);
        blank.setRole("USER");
        blank.setContent(""); // 存量脏数据：曾经落库的空白 message

        com.checkba.model.entity.ProjectAiMessage ok = new com.checkba.model.entity.ProjectAiMessage();
        ok.setId(2L);
        ok.setRole("USER");
        ok.setContent("这是一条正常的历史消息");

        ProjectAiMessageService msgSvc = mock(ProjectAiMessageService.class);
        when(msgSvc.listByConversationId("conv-1")).thenReturn(List.of(blank, ok));

        ContextAssemblerService withBlankHistory = new ContextAssemblerService(
                legalTools, msgSvc, mock(FileContextLoader.class),
                new AiContextProperties(), mockedSkillRouter(), new ClientCapabilityService(),
                new InlineContentCache(), mockedMemoryManager(), mockedCompressor(),
                mock(com.checkba.service.AppLanguageService.class),
                mockedChatModelFactory(), mock(com.checkba.service.ProjectFileService.class));

        List<ChatMessage> messages = assertDoesNotThrow(() -> withBlankHistory.assemble(
                "conv-1", "帮我修订一下", null, null, null, null, "88", AgentMode.AGENT, 1L, null),
                "空白历史消息不应掀翻整轮上下文组装");

        boolean hasValidHistoryText = messages.stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.UserMessage)
                .map(com.checkba.service.ai.context.ChatMessageText::of)
                .anyMatch("这是一条正常的历史消息"::equals);
        assertTrue(hasValidHistoryText, "跳过坏数据的同时，同一批次里有效的历史消息应正常保留");
    }

    // ---- UTF-16 代理对截断（审计条目：char-based truncation can split a surrogate pair）----

    @Test
    @DisplayName("修复：截断点恰好落在代理对中间时，整个代理对一起舍弃，不留孤立的高代理项")
    void truncateAtCharBoundaryDoesNotSplitSurrogatePair() {
        // "𠮷"（U+20BB7，罕见 CJK 扩展 B 人名字）在 UTF-16 里是高/低两个 char 的代理对
        String surrogatePair = "𠮷";
        String content = "A".repeat(10) + surrogatePair + "B".repeat(10);
        // 高代理项在下标 10，低代理项在下标 11——截断点选在两者中间
        int splitInsideSurrogate = 11;

        String truncated = ContextAssemblerService.truncateAtCharBoundary(content, splitInsideSurrogate);

        assertEquals(10, truncated.length(), "应回退到代理对开始之前，不能截出一个孤立代理项");
        assertEquals("A".repeat(10), truncated);
        assertFalse(Character.isSurrogate(truncated.charAt(truncated.length() - 1)),
                "结尾不该是孤立的代理项: " + truncated);
    }

    @Test
    @DisplayName("截断点不落在代理对中间时，行为与普通 substring 完全一致")
    void truncateAtCharBoundaryMatchesSubstringWhenNoSurrogateSplit() {
        String surrogatePair = "𠮷";
        String content = "A".repeat(10) + surrogatePair + "B".repeat(10);

        // 截断点在代理对之前：与 substring 一致
        assertEquals(content.substring(0, 5), ContextAssemblerService.truncateAtCharBoundary(content, 5));
        // 截断点在代理对之后（含完整代理对）：与 substring 一致
        assertEquals(content.substring(0, 12), ContextAssemblerService.truncateAtCharBoundary(content, 12));
        // 截断点等于原文长度（不截断）：与 substring 一致
        assertEquals(content, ContextAssemblerService.truncateAtCharBoundary(content, content.length()));
    }

    // ---- 供自建 assembler 的 mock 工厂（与 setUp 同配方）----

    // ==================== 项目上下文的设置时机 ====================

    @Test
    @DisplayName("项目上下文必须在任何一次读文件之前设置——否则附件正文被替换成 fail-closed 的报错串")
    void projectContextIsSetBeforeAnyFileRead() {
        // 病灶：ProjectContextHolder 的三行 set 原来排在附件注入与活跃文档注入之后。
        // 它是 ThreadLocal，而 ToolFileGuard.rejectIfOutsideProject 的项目归属就从它取，
        // 于是 read_document 在编排器的 @Async 线程上要么拿到 null（fail closed，返回
        // "Error: no project context ..."，这句话被原样当成文件正文注进 <file> CDATA），
        // 要么拿到上一轮遗留的**别的项目**的 id（taskExecutor 是池化复用的，assemble 从不 clear）。
        // 两种坏法都不报错，用户看到的是「AI 说读不了我的附件」。
        //
        // 生产代码里 LegalTools 是真的、会去查 holder；单测里它是 mock，所以这个顺序错误
        // 在单测中完全不可见——只能像这样把「调用发生时 holder 里是什么」直接钉住。
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>("<never called>");
        when(legalTools.read_document(anyString())).thenAnswer(inv -> {
            seen.set(com.checkba.service.ai.context.ProjectContextHolder.getProjectId());
            return "附件正文";
        });

        // 干净线程起跑，排除「上一个用例刚好留了个值」这种伪绿
        com.checkba.service.ai.context.ProjectContextHolder.clear();

        AiAgentController.ContextItem attachment = new AiAgentController.ContextItem();
        attachment.setId("777");
        attachment.setName("补充协议.docx");
        attachment.setFileType("docx");

        assembler.assemble("conv-1", "看看这份补充协议", List.of(attachment), null,
                null, null, "88", AgentMode.AGENT, 1L, null);

        assertEquals("88", seen.get(),
                "读附件时 ProjectContextHolder 里必须已经是本轮的 projectId；"
                        + "为 null 说明 set 排在了读文件之后（ToolFileGuard 会 fail closed）");
    }

    // ==================== 图片：视觉直送 vs OCR 降级 ====================

    private static AiAgentController.ContextItem imageItem(String id, String name) {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId(id);
        item.setName(name);
        item.setFileType("image");
        return item;
    }

    /** 装一个「模型支持视觉、图片字节读得到」的组装器。 */
    private ContextAssemblerService visionAssembler(byte[] bytes) throws Exception {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(true);
        com.checkba.service.ProjectFileService fileService = mock(com.checkba.service.ProjectFileService.class);
        com.checkba.model.entity.ProjectFile file = new com.checkba.model.entity.ProjectFile();
        file.setId(555L);
        file.setProjectId(88L);
        file.setName("现场照片.png");
        when(fileService.getFile(555L)).thenReturn(file);
        when(fileService.getFileBytes(555L)).thenReturn(bytes);
        return new ContextAssemblerService(
                legalTools, mockedMessageService(), mock(FileContextLoader.class),
                new AiContextProperties(), mockedSkillRouter(), new ClientCapabilityService(),
                new InlineContentCache(), mockedMemoryManager(), mockedCompressor(),
                mock(com.checkba.service.AppLanguageService.class), factory, fileService);
    }

    @Test
    @DisplayName("模型支持视觉：图片进末位用户消息的 ImageContent，且不再走 OCR")
    void visionCapableModelGetsImageContentInsteadOfOcr() throws Exception {
        ContextAssemblerService a = visionAssembler(new byte[]{1, 2, 3, 4});

        List<ChatMessage> messages = a.assemble("conv-1", "这张照片里写了什么",
                List.of(imageItem("555", "现场照片.png")), null,
                null, null, "88", AgentMode.AGENT, 1L, "moonshotai/kimi-k3");

        dev.langchain4j.data.message.UserMessage last =
                (dev.langchain4j.data.message.UserMessage) messages.get(messages.size() - 1);
        assertEquals(1, com.checkba.service.ai.context.ChatMessageText.imageCountOf(last),
                "图片应作为 ImageContent 挂在末位用户消息上");
        assertTrue(com.checkba.service.ai.context.ChatMessageText.of(last).startsWith("这张照片里写了什么"),
                "文本内容块必须排在图片之前——末位提醒的注意力位置是既有结论");

        // 同一张图绝不能既进视觉又进 OCR：那会既付图像 token 又付 OCR 的钱
        org.mockito.Mockito.verify(legalTools, org.mockito.Mockito.never()).read_document(anyString());

        String systemText = ((SystemMessage) messages.get(0)).text();
        assertTrue(systemText.contains("<image id=\"555\""),
                "system 里应留一条图片标识，让模型知道这张图随消息发了、不要再调读取工具");
    }

    @Test
    @DisplayName("模型不支持视觉：走既有 OCR 路径，且必须在上下文里明写降级原因")
    void textOnlyModelFallsBackToOcrWithExplicitNotice() {
        when(legalTools.read_document("555")).thenReturn("识别出来的文字");

        String systemText = assembleSystemTextWith(List.of(imageItem("555", "现场照片.png")));

        assertTrue(systemText.contains("识别出来的文字"), "应保留既有 OCR 正文注入");
        assertTrue(systemText.contains("当前模型不支持视觉输入"),
                "必须明写降级原因——不写的话模型会把 OCR 的识别误差当成原文事实");
        assertTrue(systemText.contains("source=\"ocr\""), "应标出正文来源是 OCR");
    }

    @Test
    @DisplayName("EN 应用语言下降级说明也必须是英文——协议面 zh/en 逐条一致是硬约束")
    void ocrFallbackNoticeFollowsAppLanguage() {
        when(legalTools.read_document("555")).thenReturn("recognized text");
        com.checkba.service.AppLanguageService en = mock(com.checkba.service.AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        ContextAssemblerService english = new ContextAssemblerService(
                legalTools, mockedMessageService(), mock(FileContextLoader.class),
                new AiContextProperties(), mockedSkillRouter(), new ClientCapabilityService(),
                new InlineContentCache(), mockedMemoryManager(), mockedCompressor(),
                en, mockedChatModelFactory(), mock(com.checkba.service.ProjectFileService.class));

        String systemText = ((SystemMessage) english.assemble("conv-1", "read it",
                List.of(imageItem("555", "photo.png")), null,
                null, null, "88", AgentMode.AGENT, 1L, "deepseek/deepseek-v4-flash").get(0)).text();

        assertTrue(systemText.contains("does not accept image input"), "英文界面下降级原因应是英文");
        assertFalse(systemText.contains("当前模型不支持视觉输入"), "英文界面下不该冒出中文说明");
    }

    @Test
    @DisplayName("超过单张体积上限的图片降级走 OCR，不是静默丢弃")
    void oversizedImageFallsBackToOcr() throws Exception {
        when(legalTools.read_document("555")).thenReturn("识别出来的文字");
        AiContextProperties props = new AiContextProperties();
        props.getVision().setMaxImageBytes(2);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(true);
        com.checkba.service.ProjectFileService fileService = mock(com.checkba.service.ProjectFileService.class);
        com.checkba.model.entity.ProjectFile file = new com.checkba.model.entity.ProjectFile();
        file.setId(555L);
        file.setProjectId(88L);
        when(fileService.getFile(555L)).thenReturn(file);
        when(fileService.getFileBytes(555L)).thenReturn(new byte[]{1, 2, 3, 4, 5});

        ContextAssemblerService a = new ContextAssemblerService(
                legalTools, mockedMessageService(), mock(FileContextLoader.class),
                props, mockedSkillRouter(), new ClientCapabilityService(),
                new InlineContentCache(), mockedMemoryManager(), mockedCompressor(),
                mock(com.checkba.service.AppLanguageService.class), factory, fileService);

        List<ChatMessage> messages = a.assemble("conv-1", "看图",
                List.of(imageItem("555", "现场照片.png")), null,
                null, null, "88", AgentMode.AGENT, 1L, "moonshotai/kimi-k3");

        assertEquals(0, com.checkba.service.ai.context.ChatMessageText.imageCountOf(
                messages.get(messages.size() - 1)), "超限的图不应直送");
        assertTrue(((SystemMessage) messages.get(0)).text().contains("识别出来的文字"),
                "超限应降级到 OCR，而不是把这张图整个丢掉");
    }

    @Test
    @DisplayName("PDF 永远不走视觉直送——open-ai 0.36 只认 Text/Image 两种内容块")
    void pdfNeverGoesThroughVisionChannel() throws Exception {
        when(legalTools.read_document("555")).thenReturn("PDF 文字层");
        ContextAssemblerService a = visionAssembler(new byte[]{1, 2, 3, 4});

        AiAgentController.ContextItem pdf = new AiAgentController.ContextItem();
        pdf.setId("555");
        pdf.setName("判决书.pdf");
        pdf.setFileType("pdf");

        List<ChatMessage> messages = a.assemble("conv-1", "看这份判决", List.of(pdf), null,
                null, null, "88", AgentMode.AGENT, 1L, "moonshotai/kimi-k3");

        assertEquals(0, com.checkba.service.ai.context.ChatMessageText.imageCountOf(
                messages.get(messages.size() - 1)), "PDF 不许当图片直送");
        assertTrue(((SystemMessage) messages.get(0)).text().contains("PDF 文字层"),
                "PDF 应继续走既有抽取/OCR 路径");
    }

    @Test
    @DisplayName("没有图片时用户消息保持纯文本构造，不退化成单元素内容块列表")
    void noImagesKeepsPlainTextUserMessage() {
        List<ChatMessage> messages = assembler.assemble("conv-1", "帮我修订一下", null, null,
                null, null, "88", AgentMode.AGENT, 1L, null);
        dev.langchain4j.data.message.UserMessage last =
                (dev.langchain4j.data.message.UserMessage) messages.get(messages.size() - 1);
        assertEquals("帮我修订一下", last.singleText(),
                "无图片时必须仍是单文本消息——全仓还有一批 singleText() 调用点靠这条");
    }

    private String assembleSystemTextWith(List<AiAgentController.ContextItem> items) {
        List<ChatMessage> messages = assembler.assemble("conv-1", "看图", items, null,
                null, null, "88", AgentMode.AGENT, 1L, "deepseek/deepseek-v4-flash");
        return ((SystemMessage) messages.get(0)).text();
    }

    /** 默认「不支持视觉」的工厂桩：既有用例全部走既有的 OCR 路径，行为保持不变。 */
    private static ChatModelFactory mockedChatModelFactory() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(false);
        return factory;
    }

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

    // ==== 易变段分隔标记（提示缓存跨轮次命中的前提） ====
    // 通道层按这个标记把 system 拆成两个 content block，只给第一块打 cache_control。
    // 标记之前的一切必须逐字节稳定，否则 Anthropic/Qwen 的缓存永远不命中——
    // 而这件事不会报错、只会静默多花钱，所以由测试钉住。

    @Test
    @DisplayName("system 恰好带一个易变段分隔标记，每轮变化的字段全在标记之后")
    void volatileFieldsLiveAfterTheSeparator() {
        String systemText = assembleSystemText(activeDoc());

        String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
        int at = systemText.indexOf(sep);
        assertTrue(at >= 0, "system 必须带易变段分隔标记");
        assertEquals(at, systemText.lastIndexOf(sep), "分隔标记只许出现一次，否则通道层拆错位置");

        String stable = systemText.substring(0, at);
        String volatilePart = systemText.substring(at + sep.length());

        assertFalse(stable.contains("Current System Time"), "秒级时间戳留在稳定块 = 缓存永不命中");
        assertFalse(stable.contains("Current Phase:"), "阶段状态每轮可变，必须在标记之后");
        assertFalse(stable.contains("## Phase Instructions"), "阶段指引跟随 Current Phase 一起走");

        assertTrue(volatilePart.contains("Current System Time"), "时间戳应落在易变块");
        assertTrue(volatilePart.contains("Current Phase:"), "阶段状态应落在易变块");
        assertTrue(volatilePart.contains("## Phase Instructions"), "阶段指引应落在易变块");

        // 稳定块仍然装着真正值得缓存的东西：指令主体 + 内联正文
        assertTrue(stable.contains("<active_document id=\"123\""), "内联正文必须留在被缓存的那一半");
    }

    @Test
    @DisplayName("同一会话连续两次组装：标记之前的字节完全相同（缓存命中的充分条件）")
    void stablePrefixIsByteIdenticalAcrossTurns() {
        when(legalTools.read_document("123")).thenReturn("第一条 合作范围……");

        String sep = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
        String first = assembleSystemText(activeDoc());
        String second = assembleSystemText(activeDoc());

        String stableFirst = first.substring(0, first.indexOf(sep));
        String stableSecond = second.substring(0, second.indexOf(sep));
        assertEquals(stableFirst, stableSecond,
                "标记之前只要差一个字节，Anthropic/Qwen 就整段重新写缓存");
    }
}
