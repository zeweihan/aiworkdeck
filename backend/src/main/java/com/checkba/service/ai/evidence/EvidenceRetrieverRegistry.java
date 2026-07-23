package com.checkba.service.ai.evidence;

import com.checkba.service.ai.mcp.McpClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * evidence.retrieve.v1 能力注册表：Spring Bean 实现（本地）+ 配置驱动的 MCP 来源统一挂在这里。
 * Skill 的 requires 声明与后续插件实现都以此为发现入口。
 */
@Service
@Slf4j
public class EvidenceRetrieverRegistry {

    private final Map<String, EvidenceRetriever> bySourceId = new LinkedHashMap<>();

    public EvidenceRetrieverRegistry(List<EvidenceRetriever> beanRetrievers,
                                     EvidenceProperties evidenceProperties,
                                     McpClientService mcpClientService) {
        for (EvidenceRetriever r : beanRetrievers) {
            bySourceId.put(r.sourceId(), r);
        }
        for (EvidenceProperties.McpSource src : evidenceProperties.getMcpSources()) {
            if (!StringUtils.hasText(src.getSourceId()) || !StringUtils.hasText(src.getServer())
                    || !StringUtils.hasText(src.getTool())) {
                log.warn("ai.evidence.mcp-sources 存在不完整配置（需 source-id/server/tool），已跳过");
                continue;
            }
            McpEvidenceRetriever retriever =
                    new McpEvidenceRetriever(src.getSourceId(), src.getServer(), src.getTool(), mcpClientService);
            bySourceId.put(retriever.sourceId(), retriever);
        }
        log.info("EvidenceRetrieverRegistry: {} 个 {} 来源: {}",
                bySourceId.size(), EvidenceRetriever.CONTRACT_VERSION, bySourceId.keySet());
    }

    /** 按来源标识查找；未注册返回 null */
    public EvidenceRetriever find(String sourceId) {
        return bySourceId.get(sourceId);
    }

    public List<EvidenceRetriever> all() {
        return new ArrayList<>(bySourceId.values());
    }
}
