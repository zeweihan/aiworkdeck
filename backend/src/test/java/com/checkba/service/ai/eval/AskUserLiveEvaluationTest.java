// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.AskUserQuestion;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.ContextAssemblerService;
import com.checkba.service.ai.InlineContentCache;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.XmlToolCallParser;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ask_user 的真实模型对照（dev-board#868，默认跳过；{@code RUN_LIVE_MODEL_CHECK=1} + {@code OPENROUTER_API_KEY}）。
 *
 * <p>回放评测用的是脚本模型，永远按剧本调对工具，证明不了「真实模型拿不准时会不会先问」。
 * 这里用<b>生产的</b> {@link ContextAssemblerService} 组装出真实的 system prompt（基底 + enforcement +
 * AGENT 模式约束 + 活跃文档正文 + 末位提醒）、<b>生产的</b>工具规格（docx 会话口径），发给真实模型，
 * 最多跑 8 个往返（原生与 XML 兜底两种协议都认，XML 用生产的 XmlToolCallParser）：读取类工具回桩输出继续跑，
 * 碰到 {@code ask_user} 或任何文档写入工具即判定。
 *
 * <ul>
 *   <li>「你能帮我清理已经打开的这个文档么」——必须调 ask_user，且在那之前不许调任何写入工具；</li>
 *   <li>「把第 12 到 21 段删掉」——必须走到写入工具，全程不许调 ask_user。</li>
 * </ul>
 *
 * <p>合成材料，不含任何真实客户数据。模型取 {@code AI_EVAL_SMOKE_MODEL}，缺省生产默认模型
 * deepseek/deepseek-v4-flash；每条跑 {@code AI_EVAL_RUNS} 次（缺省 3），温度同生产 0.7。
 *
 * <p>运行：{@code RUN_LIVE_MODEL_CHECK=1 OPENROUTER_API_KEY=... mvn test -Dtest=AskUserLiveEvaluationTest}
 */
@DisplayName("ask_user 真实模型对照（默认跳过）")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_CHECK", matches = "1")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class AskUserLiveEvaluationTest {

    private static final String DOC_ID = "1001";
    private static final String CONV = "live-ask-user";
    /** 往返上限：「先读、再定位、再选中、再删」这种写法要五六步才碰到写入工具 */
    private static final int MAX_ROUNDS = 8;

    /** 30 段合成认购协议，第 12-21 段（1 起）是混进来的一段审查意见——「清理」有好几种读法的正是这种文档。 */
    private static final List<String> PARAGRAPHS = buildParagraphs();

    private static List<String> buildParagraphs() {
        List<String> p = new ArrayList<>();
        p.add("股份认购协议");
        p.add("甲方（发行人）：星河科技股份有限公司");
        p.add("乙方（认购人）：远航投资合伙企业（有限合伙）");
        p.add("鉴于甲方拟向特定对象发行股份，乙方同意按本协议约定认购，双方经协商一致，达成如下协议：");
        p.add("第一条 认购股份：乙方认购甲方新增发行的人民币普通股 500 万股，每股面值 1 元。");
        p.add("第二条 认购价格：每股认购价格为人民币 12.80 元，认购总价款为人民币 6400 万元。");
        p.add("第三条 支付方式：乙方应于本协议生效之日起十个工作日内将认购价款一次性支付至甲方指定账户。");
        p.add("第四条 限售期：乙方认购的股份自发行结束之日起十八个月内不得转让。");
        p.add("第五条 陈述与保证：双方均具有签署和履行本协议的主体资格。");
        p.add("第六条 违约责任：任何一方违反本协议约定的，应赔偿守约方因此遭受的全部损失。");
        p.add("第七条 协议生效：本协议经双方签字盖章并经甲方股东大会审议通过后生效。");
        // 第 12-21 段：混入的审查意见
        p.add("【律师审查意见】以下为对本协议的审查意见，供内部讨论使用。");
        p.add("审查意见一：第二条未约定价格调整机制，若定价基准日至发行日期间发生除权除息，建议补充调整条款。");
        p.add("审查意见二：第三条的支付期限与发行方案不一致，建议核对。");
        p.add("审查意见三：第四条限售期十八个月与监管要求是否匹配需确认认购人身份。");
        p.add("审查意见四：第六条违约责任过于笼统，建议约定逾期付款违约金比例。");
        p.add("审查意见五：缺少协议解除条款。");
        p.add("审查意见六：缺少保密条款。");
        p.add("审查意见七：争议解决条款缺失，建议约定仲裁机构。");
        p.add("审查意见八：建议补充乙方资金来源合法性的承诺。");
        p.add("【审查意见结束】");
        p.add("第八条 保密：双方对本协议内容负有保密义务。");
        p.add("第九条 不可抗力：因不可抗力不能履行本协议的，部分或全部免除责任。");
        p.add("第十条 通知：本协议项下的通知以书面形式送达。");
        p.add("第十一条 争议解决：因本协议引起的争议，提交甲方所在地人民法院诉讼解决。");
        p.add("第十二条 其他：本协议一式四份，双方各执两份，具有同等法律效力。");
        p.add("");
        p.add("甲方（盖章）：星河科技股份有限公司");
        p.add("乙方（盖章）：远航投资合伙企业（有限合伙）");
        p.add("签署日期：2026 年 9 月 1 日");
        return p;
    }

    /**
     * ASKED = 调了 ask_user（原生或 XML 兜底）；ASKED_WITH_TAG = 用旧的 {@code <question>} 标签问了
     * （生产里同样停机等回答，但不是本卡要的结构化工具，单独计数、如实报告）；
     * WROTE = 走到了文档写入工具；ANSWERED_IN_TEXT = 既没问也没动手，直接文字收尾。
     */
    enum Outcome { ASKED, ASKED_WITH_TAG, WROTE, ANSWERED_IN_TEXT, EXHAUSTED }

    record Run(Outcome outcome, List<String> tools, String note) {
    }

    /**
     * 这是一把量真实模型行为的尺子，不是 CI 闸（env 门控、温度 0.7、结果有方差）。
     * 阈值按「病灶必须显著消失」定：先问的比例 ≥ 80%，其中多数走结构化的 ask_user 工具；
     * 明确指令一次都不许反问。每一轮的实际走向都打印出来，汇报以打印为准。
     */
    @Test
    void ambiguousCleanupAsksFirst() {
        List<Run> runs = runMany("你能帮我清理已经打开的这个文档么");
        long askedFirst = runs.stream()
                .filter(r -> r.outcome() == Outcome.ASKED || r.outcome() == Outcome.ASKED_WITH_TAG)
                .filter(r -> r.tools().stream().noneMatch(ClientCapabilityService::isDocumentWritingTool))
                .count();
        long viaTool = runs.stream().filter(r -> r.outcome() == Outcome.ASKED).count();
        System.out.printf("[ask_user live] 「清理」先问 %d/%d（其中 ask_user 工具 %d 次，<question> 标签 %d 次）%n",
                askedFirst, runs.size(), viaTool, askedFirst - viaTool);
        assertTrue(askedFirst * 5 >= runs.size() * 4L, "「清理」请求先问的比例应 ≥ 80%，实际 " + askedFirst + "/" + runs.size());
        assertTrue(viaTool * 2 >= runs.size(), "多数应走 ask_user 工具而不是旧标签，实际 " + viaTool + "/" + runs.size());
    }

    @Test
    void explicitDeletionExecutesWithoutAsking() {
        List<Run> runs = runMany("把第 12 到 21 段删掉");
        long asked = runs.stream().filter(r -> r.outcome() == Outcome.ASKED || r.outcome() == Outcome.ASKED_WITH_TAG
                || r.tools().contains(AskUserQuestion.TOOL_NAME)).count();
        long wrote = runs.stream().filter(r -> r.outcome() == Outcome.WROTE).count();
        System.out.printf("[ask_user live] 「删第 12-21 段」反问 %d/%d，走到写入工具 %d/%d%n",
                asked, runs.size(), wrote, runs.size());
        assertTrue(asked == 0, "明确指令一次都不许反问，实际 " + asked + "/" + runs.size());
        assertTrue(wrote * 5 >= runs.size() * 4L, "明确指令应直接执行（走到写入工具）≥ 80%，实际 " + wrote + "/" + runs.size());
    }

    // ---------------------------------------------------------------------------------------

    private List<Run> runMany(String prompt) {
        int n = Integer.parseInt(envOr("AI_EVAL_RUNS", "3"));
        String modelId = envOr("AI_EVAL_SMOKE_MODEL", "deepseek/deepseek-v4-flash");
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENROUTER_API_KEY"))
                .baseUrl(envOr("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
                .modelName(modelId)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(180))
                .build();

        RecordingToolRegistry registry = new RecordingToolRegistry(RealToolBeans.instantiateAll(false), new PluginService());
        registry.init();
        // 生产口径：LOWA 会话、活跃文档是 docx → 只下发 doc_* 那一族（与编排器起跑时同一个判据）
        List<ToolSpecification> specs = registry.getAllSpecifications(CONV, "doc");

        XmlToolCallParser xml = new XmlToolCallParser(registry);
        List<Run> runs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Run r = runOnce(model, specs, xml, prompt);
            runs.add(r);
            System.out.printf("[ask_user live] model=%s prompt=「%s」 run %d/%d -> %s tools=%s %s%n",
                    modelId, prompt, i, n, r.outcome(), r.tools(), r.note());
        }
        return runs;
    }

    private Run runOnce(ChatLanguageModel model, List<ToolSpecification> specs, XmlToolCallParser xml, String prompt) {
        List<ChatMessage> messages = splitVolatile(assemble(prompt));
        List<String> tools = new ArrayList<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Response<AiMessage> response = model.generate(messages, specs);
            AiMessage ai = response.content();
            if (!ai.hasToolExecutionRequests()) {
                String text = ai.text() == null ? "" : ai.text();
                // XML 兜底协议（deepseek 等模型常走这条）：与编排器同一个解析器
                if (xml.containsToolCall(text)) {
                    messages.add(ai);
                    for (XmlToolCallParser.ParsedCall call : xml.parse(text)) {
                        tools.add(call.toolName());
                        if (AskUserQuestion.TOOL_NAME.equals(call.toolName())) {
                            return new Run(Outcome.ASKED, tools, "(XML) " + abbreviate(call.argsJson()));
                        }
                        if (ClientCapabilityService.isDocumentWritingTool(call.toolName())) {
                            return new Run(Outcome.WROTE, tools, "(XML) " + call.toolName() + " " + abbreviate(call.argsJson()));
                        }
                        messages.add(dev.langchain4j.data.message.UserMessage.from(
                                "[System Tool Execution Log]\nTool: " + call.rawCode() + "\nStatus: SUCCESS\nOutput: "
                                        + readStub(call.toolName())));
                    }
                    continue;
                }
                if (text.contains("<question")) {
                    return new Run(Outcome.ASKED_WITH_TAG, tools, abbreviate(text));
                }
                return new Run(Outcome.ANSWERED_IN_TEXT, tools, abbreviate(text));
            }
            messages.add(ai);
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                tools.add(req.name());
                if (AskUserQuestion.TOOL_NAME.equals(req.name())) {
                    return new Run(Outcome.ASKED, tools, abbreviate(req.arguments()));
                }
                if (ClientCapabilityService.isDocumentWritingTool(req.name())) {
                    return new Run(Outcome.WROTE, tools, req.name() + " " + abbreviate(req.arguments()));
                }
                messages.add(ToolExecutionResultMessage.from(req, readStub(req.name())));
            }
        }
        return new Run(Outcome.EXHAUSTED, tools, "");
    }

    /** 读取类工具的桩输出：doc_get_document_text 给带段落号（0 起）的全文，其余给一句无害回执。 */
    private static String readStub(String tool) {
        if ("doc_get_selection".equals(tool)) {
            return "{\"text\":\"" + String.join("\\n", PARAGRAPHS.subList(11, 21)) + "\"}";
        }
        if ("doc_get_paragraph".equals(tool)) {
            return "{\"text\":\"" + PARAGRAPHS.get(11) + "\"}";
        }
        if ("doc_get_document_text".equals(tool) || "doc_get_outline".equals(tool)) {
            StringBuilder sb = new StringBuilder("{\"totalParagraphs\":" + PARAGRAPHS.size() + ",\"paragraphs\":[");
            for (int i = 0; i < PARAGRAPHS.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"index\":").append(i).append(",\"text\":\"")
                        .append(PARAGRAPHS.get(i).replace("\"", "\\\"")).append("\"}");
            }
            return sb.append("]}").toString();
        }
        return "{\"success\":true}";
    }

    private static List<ChatMessage> assemble(String prompt) {
        LegalTools legalTools = mock(LegalTools.class);
        when(legalTools.read_document(DOC_ID)).thenReturn(String.join("\n", PARAGRAPHS));
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
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(false);
        ContextAssemblerService assembler = new ContextAssemblerService(
                legalTools, messageService, mock(FileContextLoader.class), new AiContextProperties(), skillRouter,
                new ClientCapabilityService(), new InlineContentCache(), memoryManager, compressor,
                mock(com.checkba.service.AppLanguageService.class), factory,
                mock(com.checkba.service.ProjectFileService.class));
        AiAgentController.ContextItem active = new AiAgentController.ContextItem();
        active.setId(DOC_ID);
        active.setName("股份认购协议.docx");
        active.setFileType("docx");
        return new ArrayList<>(assembler.assemble(CONV, "run-live", prompt, null, active,
                null, null, "88", AgentMode.AGENT, 1L, null));
    }

    /** 生产通道对 deepseek 等模型把易变段拆成第二条 system 消息（OpenRouterStreamingChatModel.splitVolatileSystem）。 */
    private static List<ChatMessage> splitVolatile(List<ChatMessage> messages) {
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm && sm.text().contains(ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR)) {
                int at = sm.text().indexOf(ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR);
                out.add(SystemMessage.from(sm.text().substring(0, at)));
                out.add(SystemMessage.from(sm.text().substring(at + ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR.length())));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 400 ? one.substring(0, 400) + "..." : one;
    }

    private static String envOr(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v;
    }
}
