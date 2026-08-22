package com.checkba.service.ai.tools;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.evidence.EvidenceItem;
import com.checkba.service.ai.evidence.EvidenceQuery;
import com.checkba.service.ai.evidence.EvidenceRetriever;
import com.checkba.service.ai.evidence.EvidenceRetrieverRegistry;
import com.checkba.service.evidence.EvidenceChecks;
import com.checkba.service.evidence.EvidenceVerifyService;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchQuery;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchResult;
import com.checkba.service.evidence.EvidenceVerifyViews.LinkVerdict;
import com.checkba.service.evidence.EvidenceVerifyViews.TargetVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据面的 LLM 入口，两件事：
 * <ul>
 *   <li>{@code retrieve_evidence}——evidence.retrieve.v1 契约的检索。与 query_memory 的区别在于
 *       返回带溯源的结构化证据（稳定 ID、内容哈希、精确定位符、生效时间、更新信号），可被引用与复核。</li>
 *   <li>{@code evidence_verify}——勾稽核查（P2，dev-board#116）。把报告里已建的 EvidenceLink
 *       逐条与底稿对账，只做四类可机器校验的要素，不调 LLM。</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EvidenceTools implements AgentToolComponent {

    private final EvidenceRetrieverRegistry evidenceRetrieverRegistry;
    private final EvidenceVerifyService evidenceVerifyService;

    private static final ObjectMapper VERIFY_JSON = new ObjectMapper();
    /**
     * 摘要里最多点名多少条冲突：工具输出既进模型上下文又进过程卡（面板默认截断 4000 字），
     * 十条已经够动手了，剩下的靠 verdicts 计数与按 scope 分章节复跑。
     */
    private static final int MAX_ISSUES = 10;
    private static final int ISSUE_ANCHOR_MAX = 60;

    @Tool("检索带溯源的证据。返回每条证据的稳定ID、来源URI、内容哈希、精确定位符、生效日期与更新信号，" +
          "用于需要引用依据、核对出处或复核结论的场景。与 query_memory 的区别在于结果是可引用、可复核的结构化证据。")
    @ToolMeta(displayName = "检索证据", category = "memory")
    public String retrieve_evidence(
            @P("检索文本，描述要找依据的主张或问题") String query,
            @P("返回结果数量，默认10") int limit
    ) {
        log.info("Tool: retrieve_evidence called query='{}', limit={}", query, limit);

        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        if (limit <= 0 || limit > 20) {
            limit = 10;
        }

        Map<String, String> accessContext = new HashMap<>();
        if (ProjectContextHolder.getUserId() != null) {
            accessContext.put("userId", String.valueOf(ProjectContextHolder.getUserId()));
        }
        if (ProjectContextHolder.getConversationId() != null) {
            accessContext.put("conversationId", ProjectContextHolder.getConversationId());
        }
        EvidenceQuery evidenceQuery = new EvidenceQuery(
                String.valueOf(projectId), query, null, List.of(), accessContext, limit);

        StringBuilder sb = new StringBuilder();
        int total = 0;
        // 失败的来源要单独记：把「检索没跑成」说成「查无此据」是本工具最不能犯的错，
        // 见下方 total==0 分支
        List<String> failedSources = new java.util.ArrayList<>();
        int sourceCount = 0;
        for (EvidenceRetriever retriever : evidenceRetrieverRegistry.all()) {
            sourceCount++;
            List<EvidenceItem> items;
            try {
                items = retriever.retrieve(evidenceQuery);
            } catch (Exception e) {
                log.warn("retrieve_evidence: 来源 {} 检索失败: {}", retriever.sourceId(), e.getMessage());
                failedSources.add(retriever.sourceId());
                continue;
            }
            for (EvidenceItem item : items) {
                if (total >= limit) {
                    break;
                }
                total++;
                sb.append(total).append(". [").append(item.evidenceId()).append("] ")
                        .append(item.excerpt() == null ? "(无摘录)" : item.excerpt()).append("\n")
                        .append("   来源: ").append(item.sourceUri())
                        .append(" · 定位: ").append(item.locator());
                if (item.effectiveDate() != null) {
                    sb.append(" · 生效: ").append(item.effectiveDate());
                }
                sb.append(" · 哈希: ").append(shortHash(item.contentHash()));
                if (item.supersededAt() != null) {
                    sb.append(" · 已有更新版本（").append(item.supersededAt().toLocalDate()).append("）");
                }
                sb.append("\n");
            }
        }

        if (total == 0) {
            // 一条都没查到、而且所有来源都失败了：这**不是**「查无此据」，是「根本没查成」。
            // 契约红线（docs/EVIDENCE_CONTRACT.md「缺证据≠矛盾」）针对的是真的没有证据；
            // 把系统故障也说成查无此据，模型会据此在法律文书里断言「无相应依据」——
            // 比拿不到证据严重得多。
            if (!failedSources.isEmpty() && failedSources.size() == sourceCount) {
                return "错误：证据检索未能完成——" + failedSources.size() + " 个来源全部失败（"
                        + String.join(", ", failedSources) + "）。"
                        + "**这不代表查无此据**，不要据此断言缺少依据；请如实告知用户检索暂不可用，或稍后重试。";
            }
            String partial = failedSources.isEmpty() ? ""
                    : "（注意：" + failedSources.size() + " 个来源检索失败："
                            + String.join(", ", failedSources) + "，结果可能不完整）";
            return "未检索到相关证据。" + partial
                    + "注意：查无此据不等于结论矛盾——请如实向用户说明缺少依据，不要将缺失改写为反证。";
        }
        String partialNote = failedSources.isEmpty() ? ""
                : "（注意：" + failedSources.size() + " 个来源检索失败：" + String.join(", ", failedSources)
                        + "，以下结果可能不完整）\n";
        return "检索到 " + total + " 条证据（引用时请带证据ID）:\n\n" + partialNote + sb;
    }

    @ToolMeta(displayName = "勾稽核查", category = "document")
    @Tool("【证据】勾稽核查：把报告里已建的底稿关联（EvidenceLink）逐条与底稿对账，"
            + "只核四类可机器校验的要素——统一社会信用代码（含校验位）、日期、金额与比例（自动换算万元/亿元与千分位）、"
            + "主体名（项目 PARTY 标签及其别名）。不做语义判断、不调模型。"
            + "结论写回每个底稿位置的 relation（supports/partial/contradicts）与 confidence(0-100)。"
            + "传 linkKey 核单条；传 docFileId 核整篇，scope 可给章节前缀（如 \"一/（二）\"）只核一章。"
            + "**底稿里查不到某个要素记为 unverifiable，不等于矛盾**——别把它说成「与底稿不符」，"
            + "该写「待补」或提示用户人工核。返回精简摘要：四类结论计数 + 有矛盾的链接清单 + 续跑游标 nextOffset。")
    public String evidence_verify(
            @P("报告文件 ID（系统提醒里的 id=）；与 linkKey 二选一，给了就核整篇") Long docFileId,
            @P("只核这一条链接的 linkKey（doc_list_evidence 返回的），可不传") String linkKey,
            @P("章节前缀过滤，如 \"一/（二）\"，只在按 docFileId 核整篇时有效，可不传") String scope
    ) {
        log.info("Tool: evidence_verify called docFileId={}, linkKey={}, scope={}", docFileId, linkKey, scope);
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        Long userId = ProjectContextHolder.getUserId();
        if (projectId == null || userId == null) {
            return "Error: 缺少项目或用户上下文，无法执行勾稽核查";
        }
        boolean single = linkKey != null && !linkKey.isBlank();
        if (!single && docFileId == null) {
            return "Error: docFileId 与 linkKey 至少给一个";
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            List<LinkVerdict> verdicts;
            if (single) {
                LinkVerdict v = evidenceVerifyService.verifyLink(userId, projectId, linkKey.trim());
                verdicts = List.of(v);
                out.put("mode", "single");
                out.put("checked", 1);
            } else {
                BatchResult r = evidenceVerifyService.verifyBatch(userId, projectId,
                        new BatchQuery(docFileId, scope == null || scope.isBlank() ? null : scope.trim(),
                                null, 0, null));
                verdicts = r.links();
                out.put("mode", "batch");
                out.put("total", r.total());
                out.put("checked", r.processed());
                if (r.nextOffset() != null) {
                    out.put("nextOffset", r.nextOffset());
                    out.put("hint", "还没核完，用同样的参数再调一次即可续跑（或用 scope 按章节分批）");
                }
                if (r.cancelled()) out.put("cancelled", true);
            }
            Map<String, Integer> tally = tally(verdicts);
            out.put("verdicts", tally);
            List<Map<String, Object>> issues = issues(verdicts);
            out.put("contradictions", issues);
            if (tally.getOrDefault(EvidenceChecks.VERDICT_CONTRADICTS, 0) > issues.size()) {
                out.put("contradictionsTruncated", true);
            }
            out.put("note", "unverifiable = 底稿里查不到该要素或底稿读不出文字，**不是**与底稿矛盾；"
                    + "只有 contradictions 里的才是真冲突。");
            return VERIFY_JSON.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("evidence_verify failed docFileId={}, linkKey={}", docFileId, linkKey, e);
            return "Error: " + e.getMessage();
        }
    }

    private static Map<String, Integer> tally(List<LinkVerdict> verdicts) {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put(EvidenceChecks.VERDICT_SUPPORTS, 0);
        t.put(EvidenceChecks.VERDICT_PARTIAL, 0);
        t.put(EvidenceChecks.VERDICT_CONTRADICTS, 0);
        t.put(EvidenceChecks.VERDICT_UNVERIFIABLE, 0);
        for (LinkVerdict v : verdicts) t.merge(v.verdict(), 1, Integer::sum);
        return t;
    }

    /** 只把真冲突点名给模型：每条带锚点文字、底稿名与那条对不上的要素。 */
    private static List<Map<String, Object>> issues(List<LinkVerdict> verdicts) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (LinkVerdict v : verdicts) {
            if (!EvidenceChecks.VERDICT_CONTRADICTS.equals(v.verdict())) continue;
            for (TargetVerdict t : v.targets()) {
                if (!EvidenceChecks.VERDICT_CONTRADICTS.equals(t.verdict())) continue;
                for (EvidenceChecks.Check c : t.checks()) {
                    if (!Boolean.FALSE.equals(c.ok())) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("linkKey", v.linkKey());
                    m.put("sectionPath", v.sectionPath() == null ? "" : v.sectionPath());
                    String at = v.anchorText() == null ? "" : v.anchorText();
                    m.put("anchorText", at.length() > ISSUE_ANCHOR_MAX ? at.substring(0, ISSUE_ANCHOR_MAX) + "…" : at);
                    m.put("file", t.fileName());
                    m.put("kind", c.kind());
                    m.put("statement", c.expected());
                    m.put("draft", c.found());
                    m.put("note", c.note());
                    out.add(m);
                    if (out.size() >= MAX_ISSUES) return out;
                }
            }
        }
        return out;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
