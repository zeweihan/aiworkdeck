package com.checkba.service.ai.evidence;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.memory.MemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * evidence.retrieve.v1 的本地实现：把记忆证据账本（PR#155）落到稳定契约上。
 *
 * 映射关系：MemoryEntry → EvidenceItem
 * - evidenceId  = "memory:<id>"（主键稳定，重放一致）
 * - sourceUri   = 有来源文件时指向文件，其次对话，兜底记忆本身
 * - contentHash = sha256(memoryValue)，内容变即哈希变
 * - locator     = "memory_entry#<id>"（数据库主键即精确定位符）
 * - supersededAt = 证据账本的更新信号（同 memoryKey 存在更晚版本）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MemoryEvidenceRetriever implements EvidenceRetriever {

    static final String SOURCE_ID = "memory";
    private static final int DEFAULT_LIMIT = 10;

    private final MemoryManager memoryManager;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<EvidenceItem> retrieve(EvidenceQuery query) {
        Long projectId = parseWorkspaceId(query.workspaceId());
        if (projectId == null) {
            log.warn("evidence.retrieve.v1[memory]: 无法解析 workspaceId '{}'，返回空", query.workspaceId());
            return List.of();
        }
        int limit = query.limit() > 0 ? query.limit() : DEFAULT_LIMIT;

        List<MemoryEntry> entries;
        try {
            entries = memoryManager.retrieveMemories(projectId, query.query(), null, limit);
        } catch (Exception e) {
            log.warn("evidence.retrieve.v1[memory]: 检索失败，返回空: {}", e.getMessage());
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        List<MemoryEntry> visible = entries.stream()
                .filter(e -> e.getId() != null && e.getMemoryValue() != null)
                // 吊销语义：过期记忆不作为证据返回
                .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().isAfter(now))
                // as_of 时点：晚于时点建立的记录不可见
                .filter(e -> query.asOf() == null || e.getCreatedAt() == null
                        || !e.getCreatedAt().toLocalDate().isAfter(query.asOf()))
                // 来源过滤按作用域解释
                .filter(e -> query.sourceFilters().isEmpty()
                        || query.sourceFilters().contains(e.getScope()))
                .toList();

        Map<Long, LocalDateTime> superseded = memoryManager.findSupersededInfo(visible);

        List<EvidenceItem> items = new ArrayList<>(visible.size());
        for (MemoryEntry e : visible) {
            items.add(toItem(e, superseded.get(e.getId()), now));
        }
        return items;
    }

    private EvidenceItem toItem(MemoryEntry e, LocalDateTime supersededAt, LocalDateTime retrievedAt) {
        String sourceUri;
        List<String> provenance = new ArrayList<>();
        provenance.add("memory_entry:" + e.getId());
        if (e.getSourceFileId() != null) {
            sourceUri = "checkba://file/" + e.getSourceFileId();
            provenance.add("file:" + e.getSourceFileId());
        } else if (e.getConversationId() != null && !e.getConversationId().isBlank()) {
            sourceUri = "checkba://conversation/" + e.getConversationId();
            provenance.add("conversation:" + e.getConversationId());
        } else {
            sourceUri = "checkba://memory/" + e.getId();
        }

        String scope = e.getScope() == null ? MemoryEntry.MemoryScope.PROJECT : e.getScope();
        String accessPolicy = Boolean.TRUE.equals(e.getIsProtected()) ? scope + ":protected" : scope;

        return new EvidenceItem(
                "memory:" + e.getId(),
                sourceUri,
                sha256(e.getMemoryValue()),
                retrievedAt,
                e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate(),
                "memory_entry#" + e.getId(),
                e.getMemoryValue(),
                "text/plain",
                accessPolicy,
                provenance,
                null,
                null,
                supersededAt);
    }

    /** workspaceId 解释为项目 ID：纯数字或 "project:<id>" */
    static Long parseWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        String raw = workspaceId.startsWith("project:")
                ? workspaceId.substring("project:".length()) : workspaceId;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
