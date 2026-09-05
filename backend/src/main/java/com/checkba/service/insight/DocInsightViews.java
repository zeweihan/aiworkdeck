package com.checkba.service.insight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「依据」窗格的 REST 视图（dev-board#182）。字段形状写进 .claude/agents/doc-insight.md，前端照它接。
 *
 * <p><b>列表瘦身、明细全量</b>：列表里的 {@link EntityView#detail()} 恒为 null，
 * 只给 {@link EntityView#hasDetail()} 标志——一份文档几十个企业的工商全文能有几百 KB，
 * 轮询接口不该每两秒搬一次。findings <b>不瘦身</b>：数量少，且 detail 里带的是
 * 前端做定位与一键修改要用的数据，缺了整个 tab 就废了。
 */
public final class DocInsightViews {

    private DocInsightViews() {
    }

    public record StartResult(Long runId, Long docFileId, String status) {
    }

    public record RunView(Long id, Long docFileId, String status, String phase, String error,
                          String model, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record MentionView(String quote, Integer paragraph) {
    }

    /**
     * {@code retrievalHint}：配置类失败的结构化原因码（dev-board#458），
     * 取值 NOT_CONNECTED / NO_CREDITS / UNAUTHORIZED / NO_CREDENTIAL，其余情况为 null。
     * 前端据它把「下一步」摆成按钮（去连接账户 / 去充值），<b>不许拿 retrievalNote 做子串匹配</b>
     * ——note 双语，英文版一上线子串判定就失效。
     */
    public record EntityView(Long id, String kind, String name, String normKey,
                             String retrievalStatus, String retrievalSource, String retrievalNote,
                             String retrievalHint, boolean hasDetail, LocalDateTime fetchedAt,
                             List<MentionView> mentions, JsonNode detail) {
    }

    public record FindingView(Long id, String kind, String severity, String title, JsonNode detail) {
    }

    /** run 为 null = 这份文档还没解析过（前端显示「点解析开始」而不是转圈）。 */
    public record InsightView(RunView run, List<EntityView> entities, List<FindingView> findings) {
    }
}
