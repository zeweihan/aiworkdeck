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

    /**
     * 插件注册的外部来源（规范 v2.8 P3：evidence.retrieve.v1 公开 Provider 协议）。
     * 与构造期的内置表分开存——PluginService 在扫描/重扫时动态增删，内置表不动。
     */
    private final Map<String, EvidenceRetriever> externalBySourceId =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 注册插件来源。sourceId 与内置/已注册来源冲突时拒绝并记 ERROR（先到先得，
     * 与插件 id 去重同口径），返回是否注册成功。
     */
    public boolean registerExternal(EvidenceRetriever retriever) {
        String id = retriever.sourceId();
        if (bySourceId.containsKey(id) || externalBySourceId.putIfAbsent(id, retriever) != null) {
            log.error("evidence source '{}' 已存在，拒绝重复注册", id);
            return false;
        }
        log.info("evidence source '{}' registered (external)", id);
        return true;
    }

    /** 移除一个插件来源（幂等） */
    public void unregisterExternal(String sourceId) {
        externalBySourceId.remove(sourceId);
    }

    /** 清空全部插件来源（PluginService.rescan 重建前调用） */
    public void clearExternal() {
        externalBySourceId.clear();
    }

    /** 按来源标识查找；未注册返回 null */
    public EvidenceRetriever find(String sourceId) {
        EvidenceRetriever builtin = bySourceId.get(sourceId);
        return builtin != null ? builtin : externalBySourceId.get(sourceId);
    }

    public List<EvidenceRetriever> all() {
        List<EvidenceRetriever> out = new ArrayList<>(bySourceId.values());
        out.addAll(externalBySourceId.values());
        return out;
    }
}
