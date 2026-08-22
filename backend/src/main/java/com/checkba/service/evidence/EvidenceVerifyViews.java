package com.checkba.service.evidence;

import com.checkba.service.evidence.EvidenceChecks.Check;

import java.util.List;
import java.util.Map;

/**
 * 勾稽核查（P2）的输入/输出形状，同时是 REST 的 JSON 形状。
 */
public final class EvidenceVerifyViews {

    private EvidenceVerifyViews() {}

    /** 一个底稿位置的核查结论。verdict 见 {@link EvidenceChecks} 四值。 */
    public record TargetVerdict(Long targetId, Long fileId, String fileName, String relation, Short confidence,
                                String verdict, List<Check> checks) {}

    /** 一条锚点的核查结论；link 的 verdict = 各 target 里最坏的一个（contradicts &gt; partial &gt; supports &gt; unverifiable）。 */
    public record LinkVerdict(String linkKey, Long docFileId, String sectionPath, String anchorText,
                              String verdict, List<TargetVerdict> targets) {}

    /**
     * 批量筛选条件。{@code offset/limit} 是「本次跑多少条」的游标，不是分页查询——
     * 核查是写操作，超出上限或超时就停下并回 {@code nextOffset}，客户端接着调。
     */
    public record BatchQuery(Long docFileId, String sectionPath, String status, Integer offset, Integer limit) {}

    /**
     * 批量结果。{@code nextOffset} 非空 = 还没跑完（撞上限、超时或被取消），原样再调一次即可续跑；
     * {@code verdicts} 是本次已跑部分的四值计数。
     */
    public record BatchResult(int total, int offset, int processed, Integer nextOffset, boolean cancelled,
                              Map<String, Integer> verdicts, List<LinkVerdict> links) {}
}
