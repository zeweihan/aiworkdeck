package com.checkba.service.ai.tools;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.evidence.EvidenceItem;
import com.checkba.service.ai.evidence.EvidenceQuery;
import com.checkba.service.ai.evidence.EvidenceRetriever;
import com.checkba.service.ai.evidence.EvidenceRetrieverRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据检索工具：evidence.retrieve.v1 契约的 LLM 入口。
 *
 * 与 query_memory 的区别：本工具返回的是带溯源的结构化证据
 * （稳定 ID、内容哈希、精确定位符、生效时间、更新信号），
 * 用于"这个结论的依据是什么"类问题，输出可被引用与复核。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EvidenceTools implements AgentToolComponent {

    private final EvidenceRetrieverRegistry evidenceRetrieverRegistry;

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
        for (EvidenceRetriever retriever : evidenceRetrieverRegistry.all()) {
            List<EvidenceItem> items;
            try {
                items = retriever.retrieve(evidenceQuery);
            } catch (Exception e) {
                log.warn("retrieve_evidence: 来源 {} 检索失败: {}", retriever.sourceId(), e.getMessage());
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
                    sb.append(" · ⚠️已有更新版本（").append(item.supersededAt().toLocalDate()).append("）");
                }
                sb.append("\n");
            }
        }

        if (total == 0) {
            return "未检索到相关证据。注意：查无此据不等于结论矛盾——请如实向用户说明缺少依据，不要将缺失改写为反证。";
        }
        return "检索到 " + total + " 条证据（引用时请带证据ID）:\n\n" + sb;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
