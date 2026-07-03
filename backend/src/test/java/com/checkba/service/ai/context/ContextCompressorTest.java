package com.checkba.service.ai.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 上下文压缩器测试
 */
class ContextCompressorTest {

    private ContextCompressor compressor;
    private LegalInfoProtector legalInfoProtector;
    
    @Mock
    private ConversationSummarizer conversationSummarizer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        legalInfoProtector = new LegalInfoProtector();
        compressor = new ContextCompressor(legalInfoProtector, conversationSummarizer,
                new com.checkba.config.AiContextProperties());
        
        // 模拟摘要生成
        when(conversationSummarizer.generateQuickSummary(anyList()))
                .thenReturn("这是对话的快速摘要");
    }

    @Test
    @DisplayName("应该正确估算Token数量")
    void shouldEstimateTokensCorrectly() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("你好，请帮我查询一下公司法的相关规定。"));
        messages.add(AiMessage.from("好的，根据《公司法》第十六条规定..."));
        
        int tokens = compressor.estimateTokens(messages);
        
        // 估算应该基于字符数
        assertTrue(tokens > 0);
        assertTrue(tokens < 1000); // 简短消息不应该太多token
    }

    @Test
    @DisplayName("短对话不需要压缩")
    void shortConversationShouldNotNeedCompression() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("你好"));
        messages.add(AiMessage.from("你好！有什么可以帮助您的？"));
        
        assertFalse(compressor.needsCompression(messages));
    }

    @Test
    @DisplayName("长对话需要压缩")
    void longConversationShouldNeedCompression() {
        List<ChatMessage> messages = new ArrayList<>();
        
        // 创建大量消息
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            longContent.append("这是一段很长的法律咨询内容，包含大量的专业术语和详细说明。");
        }
        
        messages.add(UserMessage.from(longContent.toString()));
        
        assertTrue(compressor.needsCompression(messages));
    }

    @Test
    @DisplayName("压缩应该减少Token数量")
    void compressionShouldReduceTokens() {
        List<ChatMessage> messages = new ArrayList<>();
        
        // 创建多轮对话 - 生成足够多的内容触发压缩
        for (int i = 0; i < 50; i++) {
            messages.add(UserMessage.from("用户问题 " + i + "：请解释一下《公司法》的相关规定。" + 
                    "这是一个比较详细的问题，需要你详细解释法律条款的含义和适用场景。".repeat(5)));
            messages.add(AiMessage.from("助手回答 " + i + "：根据法律规定..." + 
                    "这里是一段详细的解释内容，包含法律引用和案例分析。根据《公司法》第十六条规定，公司向其他企业投资...".repeat(20)));
        }
        
        int originalTokens = compressor.estimateTokens(messages);
        
        // 使用较小的目标 token 数强制触发压缩
        int targetTokens = 1000; // 强制压缩到很小
        
        // 执行压缩
        List<ChatMessage> compressed = compressor.compress(
                messages, null, null, targetTokens);
        
        int compressedTokens = compressor.estimateTokens(compressed);
        
        // 压缩后的token数应该减少
        assertTrue(compressedTokens <= originalTokens, 
                "压缩后Token数 (" + compressedTokens + ") 应该不大于原始 (" + originalTokens + ")");
        
        // 消息数量应该减少
        assertTrue(compressed.size() < messages.size(),
                "压缩后消息数 (" + compressed.size() + ") 应该小于原始 (" + messages.size() + ")");
    }

    @Test
    @DisplayName("不超过目标Token的消息不需要压缩")
    void messagesBelowTargetShouldNotBeCompressed() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("简短问题"));
        messages.add(AiMessage.from("简短回答"));
        
        int targetTokens = 10000;
        
        List<ChatMessage> result = compressor.compress(messages, null, null, targetTokens);
        
        // 应该返回原始消息
        assertEquals(messages.size(), result.size());
    }

    @Test
    @DisplayName("压缩应该优先保留最近的消息")
    void compressionShouldPreserveRecentMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加20条消息
        for (int i = 1; i <= 20; i++) {
            messages.add(UserMessage.from("消息 " + i));
            messages.add(AiMessage.from("回复 " + i));
        }
        
        // 强制压缩到很小
        List<ChatMessage> compressed = compressor.compress(messages, null, null, 100);
        
        // 最后几条消息应该被保留
        assertTrue(compressed.stream()
                .filter(m -> m instanceof UserMessage)
                .anyMatch(m -> ((UserMessage) m).singleText().contains("20")),
                "最近的消息应该被保留");
    }

    @Test
    @DisplayName("压缩统计应该正确计算")
    void compressionStatsShouldBeCorrect() {
        ContextCompressor.CompressionStats stats = 
                ContextCompressor.CompressionStats.of(1000, 500, 20, 10);
        
        assertEquals(1000, stats.getOriginalTokens());
        assertEquals(500, stats.getCompressedTokens());
        assertEquals(20, stats.getOriginalMessageCount());
        assertEquals(10, stats.getCompressedMessageCount());
        assertEquals(0.5, stats.getCompressionRatio(), 0.01);
    }
}

