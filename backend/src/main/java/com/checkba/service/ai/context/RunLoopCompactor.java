package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行中自动 compaction：runLoop 每轮 generate 前估算消息栈 token，超阈值时把中段折叠成一条摘要。
 *
 * <p>与 {@link ContextCompressor} 的分工：那一套服务于「组装历史上下文」，会把消息重建成
 * 纯文本 UserMessage/AiMessage；放在 runLoop 里用会抹掉 AiMessage 上的 toolExecutionRequests，
 * 留下没有配对 tool_calls 的 ToolExecutionResultMessage，OpenAI 兼容通道直接 400。
 * 所以这里单独做一套结构感知的折叠：只整段丢弃中段，绝不改写保留下来的消息。
 *
 * <p>摘要是本地确定性生成、不调 LLM：compaction 触发在交互路径正中间，
 * 多插一次同步 LLM 调用等于新增一处「跑一半卡住」的成因（本地 Ollama 通道超时 300s），
 * 收益却只是文字更顺——模型真正需要的是「调过哪些工具、结果如何」，摘要行已经带上了。
 */
@Service
@RequiredArgsConstructor
public class RunLoopCompactor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunLoopCompactor.class);

    /** 摘要消息的识别标记：再次压缩时据此把上一版摘要并进新摘要，而不是当普通消息折掉 */
    static final String DIGEST_MARKER = "[上下文已压缩摘要]";

    private final AiContextProperties properties;
    private final ContextCompressor contextCompressor;

    /** 触发阈值：历史可用预算 × 触发比例（预算口径与 ContextCompressor 一致，单一来源） */
    public int triggerThreshold(String modelId) {
        int available = contextCompressor.getAvailableTokensForHistory(modelId);
        return (int) (available * properties.getCompaction().getTriggerRatio());
    }

    /**
     * 估算消息栈 token。
     *
     * <p>比 ContextCompressor.estimateTokens 多算工具调用参数与工具结果——runLoop 里这两类
     * 恰恰是大头（一次 read_document 的正文全在 ToolExecutionResultMessage 里），
     * 漏算会让阈值永远不触发。
     */
    public int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage m : messages) {
            chars += textOf(m).length();
        }
        return (int) (chars / properties.getCharsPerToken());
    }

    /**
     * 超阈值时折叠中段，否则原样返回入参列表实例（调用方据此判断是否发生了压缩）。
     * 任何异常都吞掉并返回原列表：压缩失败绝不能把一次正常的对话搞崩。
     */
    public List<ChatMessage> compact(List<ChatMessage> messages, String modelId) {
        AiContextProperties.Compaction cfg = properties.getCompaction();
        if (!cfg.isEnabled() || messages == null || messages.isEmpty()) {
            return messages;
        }
        try {
            int tokens = estimateTokens(messages);
            int threshold = triggerThreshold(modelId);
            if (threshold <= 0 || tokens <= threshold) {
                return messages;
            }

            int headEnd = headEnd(messages);
            int tailStart = tailStart(messages, headEnd, cfg.getKeepRecent());
            int middleSize = tailStart - headEnd;
            if (middleSize < cfg.getMinMiddleMessages()) {
                log.debug("Compaction skipped: middle only {} messages (tokens={}, threshold={})",
                        middleSize, tokens, threshold);
                return messages;
            }

            List<ChatMessage> middle = messages.subList(headEnd, tailStart);
            String digest = buildDigest(middle, cfg);

            List<ChatMessage> result = new ArrayList<>(messages.subList(0, headEnd));
            result.add(UserMessage.from(digest));
            result.addAll(messages.subList(tailStart, messages.size()));

            log.info("Context compaction: {} -> {} messages, {} -> {} tokens (threshold {})",
                    messages.size(), result.size(), tokens, estimateTokens(result), threshold);
            return result;
        } catch (Exception e) {
            log.warn("Context compaction failed, continuing with the original message stack", e);
            return messages;
        }
    }

    /**
     * 头部边界：开头的连续 system 消息 + 第一条用户消息（任务目标，丢了模型立刻走神）。
     * 没有用户消息时返回 0，交由中段条数下限拦住。
     */
    private static int headEnd(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                return i + 1;
            }
            if (!(messages.get(i) instanceof SystemMessage)) {
                break;
            }
        }
        return 0;
    }

    /**
     * 尾部起点：最近 keepRecent 条，再向前扩展到不以 ToolExecutionResultMessage 打头为止。
     * 工具结果与产生它的 AiMessage 必须成对保留，拆散会让 OpenAI 兼容通道直接 400。
     */
    private static int tailStart(List<ChatMessage> messages, int headEnd, int keepRecent) {
        int start = Math.max(headEnd, messages.size() - Math.max(1, keepRecent));
        while (start > headEnd && messages.get(start) instanceof ToolExecutionResultMessage) {
            start--;
        }
        return start;
    }

    /** 中段折叠成的摘要正文。前一版摘要并入，避免逐次压缩把最早的事实彻底丢干净。 */
    private String buildDigest(List<ChatMessage> middle, AiContextProperties.Compaction cfg) {
        StringBuilder prior = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (ChatMessage m : middle) {
            String text = textOf(m);
            if (text.startsWith(DIGEST_MARKER)) {
                prior.append(text.substring(DIGEST_MARKER.length()).stripLeading());
                continue;
            }
            String line = digestLine(m, text, cfg.getPerMessageChars());
            if (line != null) {
                lines.add(line);
            }
        }

        int half = Math.max(200, cfg.getDigestMaxChars() / 2);
        StringBuilder sb = new StringBuilder(DIGEST_MARKER);
        sb.append(" 以下是更早若干轮的要点，原文已折叠；缺细节请重新读取，不要凭印象作答。\n");
        if (prior.length() > 0) {
            sb.append(truncate(prior.toString(), half)).append('\n');
        }
        // 行按时间序，超预算时丢最早的几行（越近的越可能还在被引用）
        int budget = cfg.getDigestMaxChars() - sb.length();
        int from = 0;
        int used = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            used += lines.get(i).length() + 1;
            if (used > budget) {
                from = i + 1;
                break;
            }
        }
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String digestLine(ChatMessage m, String text, int perMessageChars) {
        if (m instanceof AiMessage ai) {
            StringBuilder sb = new StringBuilder("- 助手：");
            if (ai.text() != null && !ai.text().isBlank()) {
                sb.append(truncate(ai.text().trim(), perMessageChars));
            }
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                    sb.append(" 调用 ").append(r.name()).append('(')
                            .append(truncate(r.arguments(), 120)).append(')');
                }
            }
            return sb.toString();
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            String body = tr.text() == null ? "" : tr.text().trim();
            String status = body.startsWith("Error") ? "失败" : "成功";
            return "- 工具 " + tr.toolName() + " " + status + "：" + truncate(body, perMessageChars);
        }
        if (text.isBlank()) {
            return null;
        }
        String role = m instanceof SystemMessage ? "系统" : "用户";
        return "- " + role + "：" + truncate(text.trim(), perMessageChars);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 统一取文本（含工具调用参数与工具结果），估算与折叠共用同一口径。 */
    private static String textOf(ChatMessage m) {
        if (m instanceof UserMessage um) {
            // 不能用 singleText()：多模态消息（图片 + 文本）会直接抛异常
            StringBuilder sb = new StringBuilder();
            for (dev.langchain4j.data.message.Content c : um.contents()) {
                if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                    sb.append(nullToEmpty(tc.text()));
                }
            }
            return sb.toString();
        }
        if (m instanceof SystemMessage sm) {
            return nullToEmpty(sm.text());
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return nullToEmpty(tr.text());
        }
        if (m instanceof AiMessage ai) {
            StringBuilder sb = new StringBuilder(nullToEmpty(ai.text()));
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                    sb.append(r.name()).append(nullToEmpty(r.arguments()));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
