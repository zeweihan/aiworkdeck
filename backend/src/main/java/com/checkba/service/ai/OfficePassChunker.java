package com.checkba.service.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 整篇过卷的切块器（dev-board#422）。纯函数，无状态、无依赖、无模型调用。
 *
 * <p><b>块不是上下文窗口</b>：模型的内联正文一字不动，每轮都在。块只是「本轮请你
 * 处理的工作面」——让模型一次聚焦一段、逐段落笔，而不是在一轮里把整篇几十上百处
 * 一次列全（长文档下要么漏、要么单次输出超长被截断整轮丢弃）。
 * 块与块之间的勾稽关系由主模型自己把握，本类不做任何跨块推断。
 *
 * <p>切块规则：
 * <ul>
 *   <li>按 {@code \n} 切段，段落序号从 1 起，与内联正文一一对应——
 *       <b>空段落计入序号但不计入块内容</b>，序号才能和模型看到的正文对得上；</li>
 *   <li>目标块大小 {@link #TARGET_CHARS}，不切断段落；</li>
 *   <li>超过 2 倍目标的单段独立成块（不拆段：拆开会让锚点跨块，替换清单的
 *       searchText 就可能落在两块的接缝上）；</li>
 *   <li>块数上限 {@link #MAX_CHUNKS}：超长文档抬高目标块大小，而不是切出几百块——
 *       块数直接决定编排器的步数预算（{@code min(30 + total, 120)}）。</li>
 * </ul>
 *
 * <p>纯函数即「块边界在一次过卷内不变」的保证：状态里存的是段落序号区间，
 * 不是文本副本，同一份正文任何时候切出来的结果都一样。
 */
public final class OfficePassChunker {

    /** 目标块大小（字符）。一块约等于一份合同的两三条条款，够模型一次判完。 */
    static final int TARGET_CHARS = 2500;

    /** 块数上限。块数即过卷的推进步数，也是编排器抬高步数预算的依据。 */
    static final int MAX_CHUNKS = 60;

    private OfficePassChunker() {
    }

    /** 内联正文里的一段：序号（1 起，空段也占号）+ 原文。 */
    public record Paragraph(int no, String text) {
    }

    /** 一块的段落序号闭区间。 */
    public record Chunk(int fromNo, int toNo) {
    }

    /** 按 {@code \n} 切段，序号从 1 起，空段照样占号。 */
    public static List<Paragraph> paragraphs(String content) {
        List<Paragraph> out = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return out;
        }
        // -1 保留末尾空段：段落序号必须与模型看到的正文行号严格对齐
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            out.add(new Paragraph(i + 1, lines[i]));
        }
        return out;
    }

    /** 某一块的正文段落（空段落已剔除，模型不需要看空行）。 */
    public static List<Paragraph> paragraphsOf(String content, Chunk chunk) {
        List<Paragraph> out = new ArrayList<>();
        if (chunk == null) {
            return out;
        }
        for (Paragraph p : paragraphs(content)) {
            if (p.no() < chunk.fromNo() || p.no() > chunk.toNo()) continue;
            if (p.text().isBlank()) continue;
            out.add(p);
        }
        return out;
    }

    /** 某一块的字符数（只算非空段落，与 paragraphsOf 同口径）。 */
    public static int chunkChars(String content, Chunk chunk) {
        int sum = 0;
        for (Paragraph p : paragraphsOf(content, chunk)) {
            sum += p.text().length();
        }
        return sum;
    }

    /**
     * 切块。返回的区间首尾相接、覆盖全文，且不切断任何段落。
     * 正文为空（或全是空段）时返回空列表，调用方按「没什么可过卷的」处理。
     */
    public static List<Chunk> chunk(String content) {
        List<Paragraph> paras = paragraphs(content);
        if (paras.isEmpty()) {
            return List.of();
        }
        long totalChars = 0;
        for (Paragraph p : paras) {
            totalChars += p.text().length();
        }
        if (totalChars == 0) {
            return List.of();
        }
        int target = (int) Math.max(TARGET_CHARS, Math.ceil(totalChars / (double) MAX_CHUNKS));
        List<Chunk> chunks = greedy(paras, target);
        // 贪心切出来的块都「不超过目标」，所以块数总比 total/target 略多一点：
        // 按 ceil(total/60) 起步常常差一两块。这里按超出比例温和上抬（至少 +5%，
        // 且严格递增），直到收敛。目标涨到超过全文长度时必然只剩一块，循环一定终止。
        while (chunks.size() > MAX_CHUNKS && target < totalChars) {
            double factor = Math.max(1.05, chunks.size() / (double) MAX_CHUNKS);
            long next = Math.max(target + 1L, (long) Math.ceil(target * factor));
            target = (int) Math.min(totalChars, next);
            chunks = greedy(paras, target);
        }
        return chunks;
    }

    /** 贪心累积：超过目标就断，超大段独立成块。 */
    private static List<Chunk> greedy(List<Paragraph> paras, int target) {
        List<Chunk> out = new ArrayList<>();
        int from = -1;
        int last = -1;
        int cur = 0;
        for (Paragraph p : paras) {
            int len = p.text().length();
            boolean oversize = len > 2L * target;
            if (from >= 0 && (oversize || (cur > 0 && cur + len > target))) {
                out.add(new Chunk(from, last));
                from = -1;
                cur = 0;
            }
            if (from < 0) {
                from = p.no();
            }
            last = p.no();
            cur += len;
            if (oversize) {
                out.add(new Chunk(from, last));
                from = -1;
                cur = 0;
            }
        }
        if (from >= 0) {
            out.add(new Chunk(from, last));
        }
        return out;
    }
}
