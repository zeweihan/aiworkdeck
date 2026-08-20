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

    // ==================== 工具结果无模型剪枝（对标 dsh compaction-tool-result-pruner） ====================
    // 折叠摘要之前的第一道防线：中段里超长的工具结果只留首尾、掐掉中段。
    // 剪完重新估算，够了就完全不折叠——保留的原文越多，模型幻觉越少。
    // 只改 ToolExecutionResultMessage 的 text，id/toolName 原样保留，工具调用配对不受影响。

    /** 超过该字符数的工具结果才剪（阈值 > head + tail + 标记，保证剪一次必然变小、不会反复改写） */
    static final int PRUNE_THRESHOLD_CHARS = 8192;
    static final int PRUNE_HEAD_CHARS = 4096;
    static final int PRUNE_TAIL_CHARS = 1024;
    /** 剪枝标记：告诉模型中段没了、要全文就重调工具（与 DIGEST_MARKER 一样是给模型看的行内事实） */
    static final String PRUNE_MARKER = "\n\n[……工具结果中段已省略，需要全文请重新调用该工具……]\n\n";

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
     * 超阈值时先剪超长工具结果、不够再折叠中段；未触发时原样返回入参列表实例
     * （调用方据此判断是否发生了压缩）。任何异常都吞掉并返回原列表：压缩失败绝不能把一次正常的对话搞崩。
     */
    public List<ChatMessage> compact(List<ChatMessage> messages, String modelId) {
        return compact(messages, modelId, false);
    }

    /**
     * 强制压缩（上下文溢出的被动恢复通道，对标 dsh context-overflow）：服务商已用 400 证实
     * 消息栈装不下，本地估算多少已经不重要——跳过阈值判断，剪枝 + 折叠都做。
     * 返回值与 {@link #compact} 同约定：返回原列表实例 = 没有任何缩减（调用方据此放弃重试，
     * 「重试凭证 = 确实变小了」，否则原样重发必然再撞一次同样的 400）。
     */
    public List<ChatMessage> forceCompact(List<ChatMessage> messages, String modelId) {
        return compact(messages, modelId, true);
    }

    private List<ChatMessage> compact(List<ChatMessage> messages, String modelId, boolean force) {
        AiContextProperties.Compaction cfg = properties.getCompaction();
        if (!cfg.isEnabled() || messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> outcome = compactCore(messages, modelId, force);
        if (!force || outcome != messages) {
            return outcome;
        }
        // 兜底：强制压缩（服务商已用 400 证实装不下）走到这里说明中段无可折、无可剪——
        // 剩下的大头只可能在**尾部**。尾部平时刻意不剪（模型正在引用最近的结果），
        // 但此刻的选择不是「剪不剪」而是「剪一刀还是整轮直接死」：一次
        // read_document 读一份几 MB 的合同就能把单条工具结果顶到几十万字符，
        // 它落在 keepRecent 尾区，中段又不够条数，于是 forceCompact 恒返回原实例、
        // 编排器判定「压不动」终态——同一份文档每次重试都必然再撞同一个 400。
        // 只剪正文、id/toolName 原样保留，工具配对不受影响；剪枝标记会告诉模型
        // 中段没了、要全文就重新调用该工具。
        try {
            int headEnd = headEnd(messages);
            List<ChatMessage> lastResort = pruneMiddleToolResults(messages, headEnd, messages.size());
            if (lastResort != messages) {
                log.warn("Context compaction last resort: pruned oversized tool result(s) in the keepRecent tail, "
                        + "{} -> {} tokens", estimateTokens(messages), estimateTokens(lastResort));
                return lastResort;
            }
        } catch (Exception e) {
            log.warn("Last-resort tail pruning failed, keeping the original message stack", e);
        }
        return messages;
    }

    private List<ChatMessage> compactCore(List<ChatMessage> messages, String modelId, boolean force) {
        AiContextProperties.Compaction cfg = properties.getCompaction();
        try {
            int tokens = estimateTokens(messages);
            int threshold = triggerThreshold(modelId);
            if (!force && (threshold <= 0 || tokens <= threshold)) {
                return messages;
            }

            int headEnd = headEnd(messages);
            int tailStart = tailStart(messages, headEnd, cfg.getKeepRecent());

            // 第一道：无模型剪枝。剪完够用就不折叠（普通模式）；强制模式下剪枝本身也算有效缩减
            List<ChatMessage> pruned = pruneMiddleToolResults(messages, headEnd, tailStart);
            boolean prunedChanged = pruned != messages;
            if (!force && prunedChanged && estimateTokens(pruned) <= threshold) {
                log.info("Context pruning sufficed for compaction: {} -> {} tokens (threshold {})",
                        tokens, estimateTokens(pruned), threshold);
                return pruned;
            }

            List<ChatMessage> base = prunedChanged ? pruned : messages;
            int middleSize = tailStart - headEnd;
            if (middleSize < cfg.getMinMiddleMessages()) {
                log.debug("Compaction fold skipped: middle only {} messages (tokens={}, threshold={}, force={})",
                        middleSize, tokens, threshold, force);
                return base;
            }

            List<ChatMessage> middle = base.subList(headEnd, tailStart);
            String digest = buildDigest(middle, cfg);

            List<ChatMessage> result = new ArrayList<>(base.subList(0, headEnd));
            result.add(UserMessage.from(digest));
            result.addAll(base.subList(tailStart, base.size()));

            // 必须变小（对标 dsh「framedSummaryTokens >= shadowedTokens 直接失败」）：
            // 中段本来就小时，摘要的固定头部开销会让折叠后反而更大——此时折叠是净负资产，
            // 溢出恢复通道还会拿着一个更大的栈去重试、白撞一次 400。剪枝有收益就退剪枝版。
            if (estimateTokens(result) >= tokens) {
                log.info("Context compaction abandoned: fold would not shrink ({} -> {} tokens)",
                        tokens, estimateTokens(result));
                return prunedChanged ? pruned : messages;
            }

            log.info("Context compaction: {} -> {} messages, {} -> {} tokens (threshold {}, force={})",
                    messages.size(), result.size(), tokens, estimateTokens(result), threshold, force);
            return result;
        } catch (Exception e) {
            log.warn("Context compaction failed, continuing with the original message stack", e);
            return messages;
        }
    }

    /**
     * 中段（[headEnd, tailStart) 区间）里超长工具结果的首尾保留剪枝。
     * 没有可剪的就返回原列表实例；剪了返回新列表，原消息对象不动。
     *
     * <p>只动 ToolExecutionResultMessage 的正文：id 与 toolName 原样带过去，
     * 与产生它的 AiMessage(tool_calls) 的配对关系不变，OpenAI 兼容通道的结构校验不受影响。
     * 尾部 keepRecent 区间刻意不剪——最近的工具结果正在被模型引用，剪了它等于逼模型重调。
     */
    private static List<ChatMessage> pruneMiddleToolResults(List<ChatMessage> messages, int headEnd, int tailStart) {
        List<ChatMessage> result = null;
        for (int i = headEnd; i < tailStart; i++) {
            ChatMessage m = messages.get(i);
            if (!(m instanceof ToolExecutionResultMessage tr)) continue;
            String text = tr.text();
            if (text == null || text.length() <= PRUNE_THRESHOLD_CHARS) continue;
            String prunedText = text.substring(0, PRUNE_HEAD_CHARS)
                    + PRUNE_MARKER
                    + text.substring(text.length() - PRUNE_TAIL_CHARS);
            if (result == null) {
                result = new ArrayList<>(messages);
            }
            result.set(i, ToolExecutionResultMessage.from(tr.id(), tr.toolName(), prunedText));
        }
        return result == null ? messages : result;
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
