package com.checkba.service.ai.memory;

import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.context.LegalInfoProtector;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计条目：「Malformed/truncated LLM JSON response causes MemCell extraction to
 * silently produce zero memories, indistinguishable from nothing worth remembering」。
 *
 * 核心断言：parseMemCellResponse 对"确认为空"（{"memcells": []}）与"解析失败"（找不到 JSON /
 * JSON 损坏 / memcells 不是数组）必须走不同的路径——前者返回空列表，后者必须抛出
 * {@link MemCellExtractor.MemCellParseException}，不能都退化成同一个空列表。
 * 再往上一层：extractAndSave 必须把"解析失败"体现成 -1，与"正常跑完但确实没有内容"的 0 区分开。
 */
@DisplayName("MemCellExtractor：解析失败与确认为空要分得开")
class MemCellExtractorTest {

    private ChatModelFactory chatModelFactory;
    private LegalInfoProtector legalInfoProtector;
    private MemoryManager memoryManager;
    private ChatLanguageModel model;
    private MemCellExtractor extractor;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        legalInfoProtector = mock(LegalInfoProtector.class);
        memoryManager = mock(MemoryManager.class);
        model = mock(ChatLanguageModel.class);

        when(chatModelFactory.getAuxChatModel()).thenReturn(model);
        // 内容本身不含任何法律关键信息，合并阶段不会额外补 MemCell 进来
        when(legalInfoProtector.markProtectedInfo(any())).thenReturn(List.of());

        AuxModelResolver auxModelResolver = mock(AuxModelResolver.class);
        when(auxModelResolver.auxModelId()).thenReturn("qwen/qwen3.7-flash");
        TokenUsageService tokenUsageService = mock(TokenUsageService.class);

        extractor = new MemCellExtractor(chatModelFactory, legalInfoProtector, memoryManager,
                auxModelResolver, tokenUsageService);
    }

    private static List<ChatMessage> oneUserMessage(String text) {
        return List.of(UserMessage.from(text));
    }

    private void stubModelResponse(String rawText) {
        when(model.generate(any(ChatMessage.class), any(ChatMessage.class)))
                .thenReturn(Response.from(AiMessage.from(rawText)));
    }

    // ---- parseMemCellResponse 直接单测：不打真实/伪造的 LLM 调用 ----

    @Test
    @DisplayName("模型显式返回空数组 = 确认为空，正常返回空列表，不抛异常")
    void explicitEmptyArrayIsNotAFailure() {
        List<MemCellExtractor.MemCellData> cells =
                extractor.parseMemCellResponse("```json\n{\"memcells\": []}\n```");
        assertTrue(cells.isEmpty());
    }

    @Test
    @DisplayName("响应里根本没有 JSON 内容 = 解析失败，必须抛异常而不是静默返回空列表")
    void noJsonAtAllThrows() {
        assertThrows(MemCellExtractor.MemCellParseException.class,
                () -> extractor.parseMemCellResponse("抱歉，我无法完成这个任务。"));
    }

    @Test
    @DisplayName("JSON 被截断（未闭合）= 解析失败，必须抛异常")
    void truncatedJsonThrows() {
        String truncated = "```json\n{\"memcells\": [{\"type\":\"FACT\",\"key\":\"a\",\"value\":\"人民币500万元";
        assertThrows(MemCellExtractor.MemCellParseException.class,
                () -> extractor.parseMemCellResponse(truncated));
    }

    @Test
    @DisplayName("memcells 被截断成对象而不是数组 = 解析失败，必须抛异常")
    void memcellsAsObjectThrows() {
        assertThrows(MemCellExtractor.MemCellParseException.class,
                () -> extractor.parseMemCellResponse("```json\n{\"memcells\": {\"type\":\"FACT\"}}\n```"));
    }

    // ---- extractAndSave 端到端：-1（解析失败） vs 0（确认为空）必须能区分 ----

    @Test
    @DisplayName("extractAndSave：LLM 响应解析失败时返回 -1，不是 0")
    void extractAndSaveReturnsNegativeOneOnParseFailure() {
        stubModelResponse("这不是 JSON，也没有代码块。");

        int saved = extractor.extractAndSave(1L, "conv-1", oneUserMessage("这是第一条消息，凑够消息数门槛"));

        assertEquals(-1, saved, "解析失败必须能和「正常跑完、确实没有可提取内容」的 0 区分开");
    }

    @Test
    @DisplayName("extractAndSave：模型确认无可提取信息时返回 0，不是 -1")
    void extractAndSaveReturnsZeroOnConfirmedEmpty() {
        stubModelResponse("```json\n{\"memcells\": []}\n```");

        int saved = extractor.extractAndSave(1L, "conv-1", oneUserMessage("这是第一条消息，凑够消息数门槛"));

        assertEquals(0, saved, "确认为空不该被误判成解析失败");
    }
}
