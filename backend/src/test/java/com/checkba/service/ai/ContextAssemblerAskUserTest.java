// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import dev.langchain4j.data.message.UserMessage;
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
 * 「拿不准先问」的提示词接线（dev-board#868）：规则挂在用户消息<b>末位</b>、只在 AGENT 模式、
 * 用户这条就是对 ask_user 的回答时换成「按回答继续」；system 里的停机条件两种语言都点名 ask_user。
 */
@DisplayName("ask_user：提示词末位规则与回答回填")
class ContextAssemblerAskUserTest {

    private ContextAssemblerService assembler;
    private com.checkba.service.AppLanguageService appLanguageService;
    private LegalTools legalTools;

    private static final String ANSWER = "<ask_user_answer id=\"ask-1\">\nQuestion: 「清理」具体指哪一种？\n"
            + "Selected:\n- 删除混入的审查报告\n</ask_user_answer>";

    @BeforeEach
    void setUp() {
        legalTools = mock(LegalTools.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        when(contextCompressor.needsCompression(any(), any())).thenReturn(false);
        appLanguageService = mock(com.checkba.service.AppLanguageService.class);
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        assembler = new ContextAssemblerService(
                legalTools, messageService, mock(FileContextLoader.class),
                new AiContextProperties(), skillRouter, new ClientCapabilityService(), new InlineContentCache(),
                memoryManager, contextCompressor, appLanguageService,
                chatModelFactory, mock(com.checkba.service.ProjectFileService.class));
    }

    private List<ChatMessage> assemble(String prompt, AgentMode mode, AiAgentController.ContextItem active) {
        return assembler.assemble("conv-1", "run-1", prompt, null, active, null, null, "88", mode, 1L, null);
    }

    private static String lastUserText(List<ChatMessage> messages) {
        return ((UserMessage) messages.get(messages.size() - 1)).singleText();
    }

    private static AiAgentController.ContextItem activeDoc() {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId("123");
        item.setName("股份认购协议.docx");
        item.setFileType("docx");
        return item;
    }

    @Test
    void ambiguousRequestGetsTheClarificationRuleAtTheVeryEnd() {
        when(legalTools.read_document("123")).thenReturn("第一条 认购……");
        String text = lastUserText(assemble("你能帮我清理已经打开的这个文档么", AgentMode.AGENT, activeDoc()));
        assertTrue(text.startsWith("你能帮我清理已经打开的这个文档么"));
        int activeDocReminder = text.indexOf("编辑器中当前已打开文档");
        int rule = text.indexOf("先调用 ask_user");
        assertTrue(rule > 0, text);
        assertTrue(activeDocReminder < rule, "ask_user 规则必须排在活跃文档提醒之后（最末位）: " + text);
        assertTrue(text.contains("句末是「吗」「么」「？」"), "疑问句判据要点名: " + text);
        assertTrue(text.contains("不要写任务清单"), "病灶里模型先写了任务清单再开删: " + text);
        assertTrue(text.endsWith("直接执行，不要反问。"), "规则必须两头都说——含「指令明确就直接做」的反例: " + text);
    }

    @Test
    void answerMessageSwitchesTheTailToContinueAndNeverAsksAgain() {
        String text = lastUserText(assemble(ANSWER, AgentMode.AGENT, null));
        assertTrue(text.startsWith(ANSWER), "回答原样进入下一轮，模型才知道答的是哪一问: " + text);
        assertTrue(text.contains("用户对你上一轮 ask_user 提问的回答"), text);
        assertTrue(text.contains("不要再就同一件事提问"), text);
        assertFalse(text.contains("先调用 ask_user"), "拿着回答又被催去再问一遍: " + text);
    }

    @Test
    void askAndPlanModesGetNoTailRule() {
        for (AgentMode mode : List.of(AgentMode.ASK, AgentMode.PLAN)) {
            String text = lastUserText(assemble("你能帮我清理一下么", mode, null));
            assertEquals("你能帮我清理一下么", text, mode + " 模式不挂 ask_user 规则");
        }
    }

    @Test
    void englishModeUsesTheEnglishRulesAndStopCondition() {
        when(appLanguageService.isEnglish()).thenReturn(true);
        List<ChatMessage> messages = assemble("Can you clean up this document?", AgentMode.AGENT, null);
        String tail = lastUserText(messages);
        assertTrue(tail.contains("call the ask_user tool (not just a <question> tag) with 2-4 concrete options"), tail);
        assertTrue(tail.contains("do not edit the document or write a task list before the user answers"), tail);
        assertTrue(tail.contains("just do it - do not ask back."), tail);
        String system = ((SystemMessage) messages.get(0)).text();
        assertTrue(system.contains("**ALSO STOP** once you call the `ask_user` tool"), "英文停机条件");
        assertTrue(system.contains("call `ask_user` with 2-4 concrete"), "英文 AGENT 模式约束");
        assertTrue(system.contains("## Clarification (`ask_user` Tool / `<question>` Tag)"), "英文基底 prompt");

        String answerTail = lastUserText(assemble(ANSWER, AgentMode.AGENT, null));
        assertTrue(answerTail.contains("This message is the user's answer to your ask_user question"), answerTail);
    }

    @Test
    void chineseSystemPromptNamesAskUserInStopConditionModeRulesAndClarification() {
        String system = ((SystemMessage) assemble("你好", AgentMode.AGENT, null).get(0)).text();
        assertTrue(system.contains("**ALSO STOP** once you call the `ask_user` tool"), "停机条件");
        assertTrue(system.contains("<ask_user_answer>"), "停机条件要说明回答长什么样");
        assertTrue(system.contains("先调 `ask_user` 给 2-4 个具体选项"), "AGENT 模式约束");
        assertTrue(system.contains("**首选 `ask_user` 工具**"), "基底 prompt Clarification 一节");
        assertTrue(system.contains("「把第 12 到 21 段删掉」"), "反例也要在");
    }
}
