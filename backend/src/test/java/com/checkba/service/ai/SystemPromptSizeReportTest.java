// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.context.ChatMessageText;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * 系统提示逐段体量（dev-board#1073 第一步：只量，不设上限）。
 *
 * <p>用<b>生产的</b> {@link ContextAssemblerService#assemble} 组装五种会话 × 两种语言的 system 消息，
 * 按拼装顺序把它切成逐段，打印一张 Markdown 表。切段不靠手拼：基底与片段直接读 classpath 上的
 * 同一份资源，模式约束与工具目录段反射调生产的包私有/私有方法，其余按生产写入的标记定位；
 * 各段字符数之和必须逐字节等于整段 system 的字符数，差一个都算失败。
 *
 * <p>渐进披露与活跃文档类目裁剪按生产默认（两个都开）装配，所以「工具目录」段在 AGENT 会话里有值。
 * 记忆、skill、附件都不给（mock 返回空），易变段只剩 Current Context 与 Phase Instructions。
 *
 * <p>运行：{@code mvn -q test -Dtest=SystemPromptSizeReportTest}
 */
class SystemPromptSizeReportTest {

    private static final String SEP = ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR;
    private static final String PLACEHOLDER = ContextAssemblerService.TOOL_GUIDANCE_PLACEHOLDER;

    /** 约 500 字符的合成合同片段（不含任何真实客户数据）。四种带活跃文档的会话共用同一份，便于横向比较。 */
    private static final String INLINE_BODY = """
            股权转让协议（节选）
            甲方（转让方）：北辰投资管理有限公司
            乙方（受让方）：远航科技股份有限公司
            鉴于甲方合法持有目标公司星河数据技术有限公司百分之三十的股权，乙方有意受让该等股权，双方经友好协商，达成如下协议：
            第一条 转让标的：甲方同意将其持有的目标公司百分之三十股权（对应注册资本人民币三百万元）转让给乙方，乙方同意受让。
            第二条 转让价款：本次股权转让价款为人民币四千五百万元。乙方应于本协议生效之日起十五个工作日内支付首期价款人民币二千万元，其余价款于工商变更登记完成之日起十个工作日内付清。
            第三条 交割：甲方应于收到首期价款之日起二十个工作日内配合目标公司办理股东变更登记手续，交割日为变更登记完成之日。
            第四条 陈述与保证：甲方保证其对转让股权享有完整的所有权，该等股权不存在质押、冻结或其他权利负担；乙方保证其具备受让股权的主体资格与支付能力。
            第五条 过渡期安排：自本协议签署之日至交割日，甲方应促使目标公司在正常经营过程中开展业务，不得进行重大资产处置、对外担保或利润分配。
            第六条 违约责任：任何一方违反本协议约定，应赔偿守约方因此遭受的全部损失；乙方逾期付款的，每逾期一日按应付未付金额的万分之五支付违约金。
            第七条 争议解决：因本协议引起的争议，提交北京仲裁委员会按其届时有效的仲裁规则仲裁。
            """;

    /** 五种会话：能力档 + 宿主 + 活跃文档（null = 没开文档）。 */
    enum Session {
        DOCX("docx（LOWA 文字）", "lowa", null, "股权转让协议.docx", "docx"),
        XLSX("xlsx（LOWA 表格）", "lowa", null, "股权结构表.xlsx", "xlsx"),
        PPTX("pptx（LOWA 演示）", "lowa", null, "项目汇报.pptx", "pptx"),
        WORD_PANE("Word 任务窗格", "office", "word", "股权转让协议.docx", "docx"),
        NONE("纯对话", "none", null, null, null);

        final String label;
        final String capability;
        final String officeHost;
        final String docName;
        final String fileType;

        Session(String label, String capability, String officeHost, String docName, String fileType) {
            this.label = label;
            this.capability = capability;
            this.officeHost = officeHost;
            this.docName = docName;
            this.fileType = fileType;
        }

        AiAgentController.ContextItem activeContext() {
            if (docName == null) return null;
            AiAgentController.ContextItem item = new AiAgentController.ContextItem();
            item.setId("1001");
            item.setName(docName);
            item.setFileType(fileType);
            item.setInlineContent(INLINE_BODY);
            return item;
        }
    }

    record Row(String name, String text) {
        int chars() {
            return text.length();
        }
    }

    /** token 计数口径：优先真实 tokenizer（jtokkit o200k_base，与 gpt-4o 同一套），拿不到退启发式。 */
    private static final Tokenizer TOKENIZER = loadTokenizer();
    private static final String TOKEN_BASIS = TOKENIZER != null
            ? "OpenAiTokenizer(\"gpt-4o\") = jtokkit o200k_base 实数（OpenAI 分词，DeepSeek/Qwen/Kimi 自家分词会有出入）"
            : "启发式：CJK 1 字≈1 token，其余 4 字符≈1 token";

    private static Tokenizer loadTokenizer() {
        try {
            OpenAiTokenizer t = new OpenAiTokenizer("gpt-4o");
            t.estimateTokenCountInText("probe 探针");
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    static int tokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (TOKENIZER != null) return TOKENIZER.estimateTokenCountInText(s);
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
                    || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return cjk + (other + 3) / 4;
    }

    @Test
    @DisplayName("系统提示逐段体量：5 种会话 × 中英，逐段字符数之和逐字节等于整段")
    void reportPerSegmentSizes() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("\n# 系统提示逐段体量（dev-board#1073）\n\n");
        out.append("- token 口径：").append(TOKEN_BASIS).append("\n");
        out.append("- 占比口径：按字符数占整段 system 的比例；逐段 token 分别计数，各段之和与整段计数可能差几个 token（切点处的合并）\n");
        out.append("- 装配：AgentMode.AGENT；渐进披露 + 活跃文档类目裁剪按生产默认开；无 skill、无附件、无记忆、无 plan/taskList；");
        out.append("活跃文档四种会话共用同一份约 ").append(INLINE_BODY.length()).append(" 字符的合成合同正文（内联）\n");

        for (boolean english : new boolean[] {false, true}) {
            for (Session session : Session.values()) {
                out.append(reportOne(session, english));
            }
        }

        out.append(toolSpecComparison());
        System.out.println(out);
    }

    private String reportOne(Session session, boolean english) throws Exception {
        String conv = "size-report-" + session.name().toLowerCase() + (english ? "-en" : "-zh");
        ClientCapabilityService capabilities = new ClientCapabilityService();
        capabilities.record(conv, session.capability, session.officeHost);
        ContextAssemblerService assembler = newAssembler(capabilities, english);
        AiAgentController.ContextItem active = session.activeContext();

        List<ChatMessage> messages = assembler.assemble(conv, "run-" + conv, "帮我看一下这份文件",
                null, active, null, null, "88", AgentMode.AGENT, 1L, null);
        String system = ((SystemMessage) messages.get(0)).text();
        String lastUser = ChatMessageText.of(messages.get(messages.size() - 1));

        // ---- 结构断言 ----
        int sepIdx = system.indexOf(SEP);
        assertTrue(sepIdx >= 0, "分隔标记必须出现");
        assertEquals(sepIdx, system.lastIndexOf(SEP), "分隔标记必须恰好出现一次");
        if (active != null) {
            int bodyIdx = system.indexOf(INLINE_BODY);
            assertTrue(bodyIdx >= 0, "内联正文应原样进 system");
            assertTrue(bodyIdx + INLINE_BODY.length() <= sepIdx, "内联正文必须在分隔标记之前（稳定段）");
        }

        String stem = ContextAssemblerService.toolGuidanceStem(
                capabilities.capabilityOf(conv), capabilities.officeHostOf(conv));
        List<Row> rows = segment(assembler, system, english, stem, active);

        int sum = rows.stream().mapToInt(Row::chars).sum();
        StringBuilder joined = new StringBuilder();
        rows.forEach(r -> joined.append(r.text()));
        assertEquals(system.length(), sum, session + (english ? " en" : " zh") + "：逐段字符数之和必须等于整段");
        assertEquals(system, joined.toString(), "各段按序拼回必须逐字节等于整段 system");

        // ---- 打印 ----
        StringBuilder t = new StringBuilder();
        t.append("\n## ").append(session.label).append(" · ").append(english ? "en" : "zh")
                .append("（能力片段 ").append(stem).append(english ? ".en.md" : ".md").append("）\n\n");
        t.append("| 段名 | 字符数 | 估算 token | 占比 |\n|---|---:|---:|---:|\n");
        for (Row r : rows) {
            t.append("| ").append(r.name()).append(" | ").append(r.chars()).append(" | ")
                    .append(tokens(r.text())).append(" | ")
                    .append(String.format("%.1f%%", 100.0 * r.chars() / system.length())).append(" |\n");
        }
        String stable = system.substring(0, sepIdx);
        String volatilePart = system.substring(sepIdx + SEP.length());
        int bodyChars = active == null ? 0 : INLINE_BODY.length();
        int bodyTokens = active == null ? 0 : tokens(INLINE_BODY);
        t.append("\n汇总（token 为整段一次计数）：\n");
        t.append(String.format("- 稳定段合计（标记之前）：%d 字符 / %d token%n", stable.length(), tokens(stable)));
        t.append(String.format("- 固定块合计（稳定段减内联正文）：%d 字符 / %d token%n",
                stable.length() - bodyChars, tokens(stable) - bodyTokens));
        t.append(String.format("- 分隔标记：%d 字符（通道层吃掉，不发给模型）%n", SEP.length()));
        t.append(String.format("- 易变段合计（标记之后）：%d 字符 / %d token%n", volatilePart.length(), tokens(volatilePart)));
        t.append(String.format("- 整段 system 合计：%d 字符 / %d token%n", system.length(), tokens(system)));
        t.append(String.format("- （参考）末位用户消息含系统提醒：%d 字符 / %d token%n", lastUser.length(), tokens(lastUser)));
        return t.toString();
    }

    /** 按生产拼装顺序切段。每一段都在 system 里定位并断言相接，最后由调用方校验和号。 */
    private List<Row> segment(ContextAssemblerService assembler, String s, boolean english, String stem,
                              AiAgentController.ContextItem active) throws Exception {
        List<Row> rows = new ArrayList<>();

        // 1) 基底 + 能力片段（spliceToolGuidance：占位标记整个被片段替换）
        String base = resource(english ? "prompts/system_prompt.en.md" : "prompts/system_prompt.md");
        String fragment = english ? resource("prompts/" + stem + ".en.md") : resource("prompts/" + stem + ".md");
        int ph = base.indexOf(PLACEHOLDER);
        assertTrue(ph >= 0 && ph == base.lastIndexOf(PLACEHOLDER), "基底里的工具指引占位标记应恰好一处");
        String pre = base.substring(0, ph);
        String post = base.substring(ph + PLACEHOLDER.length());
        String spliced = pre + fragment + post;
        assertTrue(s.startsWith(spliced), "system 应以「基底（占位处换成片段）」开头");
        splitByHeading(rows, pre, "# ", "基底 / ", "基底 / 文件头（首个 # 之前）");
        splitByHeading(rows, fragment, "## ", "片段 " + stem + " / ", "片段 " + stem + " / 抬头（注释 + 一级标题）");
        splitByHeading(rows, post, "# ", "基底 / ", "基底 / Tool Usage Guidelines（占位处之后的尾巴）");
        int pos = spliced.length();

        // 2) enforcement：一直到模式约束开头
        String mode = (String) invoke(assembler, english ? "getModeConstraintsEn" : "getModeConstraints",
                new Class<?>[] {AgentMode.class}, AgentMode.AGENT);
        int modeIdx = s.indexOf(mode, pos);
        assertTrue(modeIdx >= pos, "模式约束应在 enforcement 之后");
        String enforcement = s.substring(pos, modeIdx);
        assertTrue(enforcement.contains("# SYSTEM ENFORCEMENT"), "enforcement 段应紧跟基底");
        rows.add(new Row("enforcement（# SYSTEM ENFORCEMENT）", enforcement));
        rows.add(new Row("模式约束（getModeConstraints" + (english ? "En" : "") + " AGENT）", mode));
        pos = modeIdx + mode.length();

        // 3) 工具目录段（toolDisclosureRule）
        String disclosure = (String) invoke(assembler, "toolDisclosureRule",
                new Class<?>[] {boolean.class, AiAgentController.ContextItem.class, AgentMode.class},
                english, active, AgentMode.AGENT);
        assertTrue(s.startsWith(disclosure, pos), "工具目录段应紧跟模式约束");
        rows.add(new Row("工具目录（toolDisclosureRule）", disclosure));
        pos += disclosure.length();

        // 4) skill 注入 / User Context Files：本测试都不给，记 0
        rows.add(new Row("skill 注入", ""));
        String stableSoFar = s.substring(0, s.indexOf(SEP));
        assertFalse(stableSoFar.contains("# User Context Files"), "本测试不带附件");
        rows.add(new Row("# User Context Files", ""));

        // 5) 活跃文档：指引 / 标签壳 / 内联正文
        if (active != null) {
            int tag = s.indexOf("<active_document id=", pos);
            assertTrue(tag >= pos, "应有 <active_document> 标签");
            int body = s.indexOf(INLINE_BODY, tag);
            String close = "\n]]></active_document>\n";
            assertTrue(s.startsWith(close, body + INLINE_BODY.length()), "内联正文之后应紧跟闭合标签");
            rows.add(new Row("# Active Document 指引（不含正文）", s.substring(pos, tag)));
            rows.add(new Row("<active_document> 开标签", s.substring(tag, body)));
            rows.add(new Row("内联正文（随文档变，不算固定块）", INLINE_BODY));
            rows.add(new Row("</active_document> 闭标签", close));
            pos = body + INLINE_BODY.length() + close.length();
        } else {
            rows.add(new Row("# Active Document 指引（不含正文）", ""));
            rows.add(new Row("内联正文（随文档变，不算固定块）", ""));
        }

        // 6) 活跃文档之后、分隔标记之前：move_files_batch 批量规则（非 Office 会话才有）
        int sepIdx = s.indexOf(SEP);
        assertTrue(sepIdx >= pos, "分隔标记应在所有稳定段之后");
        rows.add(new Row("文件整理批量规则（move_files_batch，非 Office 会话）", s.substring(pos, sepIdx)));

        // 7) 分隔标记 + 易变段
        rows.add(new Row("分隔标记（SYSTEM_VOLATILE_SEPARATOR）", SEP));
        String v = s.substring(sepIdx + SEP.length());
        int phase = v.indexOf("\n\n## Phase Instructions");
        assertTrue(phase >= 0, "易变段应有 Phase Instructions");
        int memory = v.indexOf("\n\n# ", phase);
        if (memory < 0) memory = v.length();
        rows.add(new Row("易变 / # Current Context", v.substring(0, phase)));
        rows.add(new Row("易变 / ## Phase Instructions", v.substring(phase, memory)));
        rows.add(new Row("易变 / 记忆各段", v.substring(memory)));
        return rows;
    }

    /**
     * 按行首标题切段（跳过 ``` 围栏里的行）。第一个标题之前的部分用 {@code leadLabel}（空就不出行）。
     */
    private static void splitByHeading(List<Row> rows, String text, String marker, String prefix, String leadLabel) {
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? text.length() : nl;
            String line = text.substring(i, end);
            if (line.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence && line.startsWith(marker)) {
                starts.add(i);
                names.add(line.substring(marker.length()).trim());
            }
            i = nl < 0 ? text.length() : nl + 1;
        }
        int first = starts.isEmpty() ? text.length() : starts.get(0);
        if (first > 0) rows.add(new Row(leadLabel, text.substring(0, first)));
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k);
            int to = k + 1 < starts.size() ? starts.get(k + 1) : text.length();
            rows.add(new Row(prefix + names.get(k), text.substring(from, to)));
        }
    }

    /** 对照行：docx 会话在生产默认（渐进披露 + 类目裁剪都开）下首轮下发的工具规格，口径同 ToolSchemaBudgetTest.wireBytes。 */
    private String toolSpecComparison() {
        try {
            Class<?> beans = Class.forName("com.checkba.service.ai.eval.RealToolBeans");
            Method m = beans.getDeclaredMethod("instantiateAll");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<com.checkba.service.ai.tools.AgentToolComponent> components = (List<com.checkba.service.ai.tools.AgentToolComponent>) m.invoke(null);
            com.checkba.service.ai.eval.RecordingToolRegistry registry =
                    new com.checkba.service.ai.eval.RecordingToolRegistry(components, new PluginService());
            registry.init();
            ToolDisclosurePolicy production = new ToolDisclosurePolicy(true, true);
            List<ToolSpecification> docx =
                    registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);
            List<ToolSpecification> full = docx.stream()
                    .filter(sp -> !ToolDisclosurePolicy.CATALOG_TOOL.equals(sp.name()))
                    .toList();
            List<ToolSpecification> shipped = production.narrow(
                    production.trimForDocKind(docx, ClientCapabilityService.DOC_KIND_WRITER, Set.of()), Set.of());
            String fullJson = wireJson(full);
            String shippedJson = wireJson(shipped);
            return "\n## 对照：docx 会话首轮工具规格（生产默认，口径同 ToolSchemaBudgetTest.wireBytes；不含编排器另补的 memory_*）\n\n"
                    + "| 口径 | 工具个数 | 上线路字节 | 估算 token |\n|---|---:|---:|---:|\n"
                    + "| 两个开关都关（全集） | " + full.size() + " | " + fullJson.length() + " | " + tokens(fullJson) + " |\n"
                    + "| 生产默认（核心集） | " + shipped.size() + " | " + shippedJson.length() + " | " + tokens(shippedJson) + " |\n";
        } catch (Throwable e) {
            return "\n## 对照：docx 会话首轮工具规格 —— 未能计算（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）\n";
        }
    }

    private static String wireJson(List<ToolSpecification> specs) {
        if (specs.isEmpty()) return "";
        String json = dev.ai4j.openai4j.Json.toJson(
                dev.langchain4j.model.openai.InternalOpenAiHelper.toTools(specs, false));
        return json.replaceAll("[ \\t\\n\\r]", "");
    }

    // ---------------------------------------------------------------------------------------

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ContextAssemblerService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static String resource(String path) throws Exception {
        try (var in = SystemPromptSizeReportTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "classpath 上缺 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ContextAssemblerService newAssembler(ClientCapabilityService capabilities, boolean english) {
        LegalTools legalTools = mock(LegalTools.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.needsCompression(any(), any())).thenReturn(false);
        com.checkba.service.AppLanguageService lang = mock(com.checkba.service.AppLanguageService.class);
        when(lang.isEnglish()).thenReturn(english);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(false);
        ContextAssemblerService assembler = new ContextAssemblerService(
                legalTools, messageService, mock(FileContextLoader.class),
                new AiContextProperties(), skillRouter, capabilities, new InlineContentCache(),
                memoryManager, compressor, lang, factory, mock(com.checkba.service.ProjectFileService.class));
        assembler.setToolDisclosurePolicy(new ToolDisclosurePolicy(true, true));
        return assembler;
    }
}
