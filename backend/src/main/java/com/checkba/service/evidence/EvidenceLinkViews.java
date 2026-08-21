package com.checkba.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * EvidenceLinkService 的输入/输出形状（同时是 REST 的 JSON 形状，供 Controller / SDK / P1 工具共用）。
 */
public final class EvidenceLinkViews {

    private EvidenceLinkViews() {}

    /** 建链/追加时的一个底稿位置；updateTarget 时 null 字段 = 不改。 */
    public record TargetInput(Long fileId, String locatorJson, String relation, String method, Short confidence, String note) {}

    public record FileBrief(Long id, String name, String fileType, Long parentId, boolean isDeleted) {}

    public record TargetView(Long id, Long fileId, FileBrief file, JsonNode locator, String relation, String method,
                             Short confidence, String note) {}

    public record LinkView(Long id, String linkKey, Long docFileId, String anchorText, String anchorHash,
                           String sectionPath, String sectionTitle, String status, String createdByKind,
                           LocalDateTime createdAt, LocalDateTime updatedAt, List<TargetView> targets) {}

    /** worker check_link_anchors 的一条核对结果。 */
    public record AnchorReport(String linkKey, boolean exists, String text) {}
}
