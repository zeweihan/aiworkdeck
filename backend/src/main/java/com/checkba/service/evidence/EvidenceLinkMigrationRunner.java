package com.checkba.service.evidence;

import com.checkba.model.entity.DocFileLink;
import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DocFileLinkRepository;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * doc_file_link → evidence_link 启动迁移（spec §1.5）。幂等：evidence_link 非空即跳过。
 * 每行 → 一条 link（link_key 原值、status=unverified、doc_file_id 按 (project_id, wps_file_id) 反查，
 * 反查不到的行跳过并计数）+ fileIdsJson 每个 fileId 一条 target（locator 空、supports、human）。
 * 旧表保留一个发版周期，由 DocFileLinkController 只读代理到新 Service。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvidenceLinkMigrationRunner implements ApplicationRunner {

    private final DocFileLinkRepository old;
    private final EvidenceLinkRepository links;
    private final EvidenceLinkTargetRepository targets;
    private final ProjectFileRepository files;
    private final ObjectMapper om;

    private int lastMigrated;
    private int lastSkipped;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        lastMigrated = 0;
        lastSkipped = 0;
        if (links.count() > 0) return;
        // DocFileLink 实体仍在、表由 JPA 建好，这里不包 try/catch：事务内 catch 仓库异常救不了启动（已 rollback-only）
        List<DocFileLink> rows = old.findAll();
        if (rows.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (DocFileLink r : rows) {
            ProjectFile doc = files.findFirstByProjectIdAndWpsFileId(r.getProjectId(), r.getDocWpsFileId()).orElse(null);
            if (doc == null) {
                lastSkipped++;
                log.warn("EvidenceLink 迁移跳过：找不到报告文件 projectId={} wpsFileId={} linkKey={}",
                        r.getProjectId(), r.getDocWpsFileId(), r.getLinkKey());
                continue;
            }
            EvidenceLink l = new EvidenceLink();
            l.setProjectId(r.getProjectId());
            l.setDocFileId(doc.getId());
            l.setLinkKey(r.getLinkKey());
            l.setAnchorText(r.getAnchorText());
            l.setAnchorHash(AnchorHash.of(r.getAnchorText()));
            l.setStatus(EvidenceLink.STATUS_UNVERIFIED);
            l.setCreatedByKind(EvidenceLink.KIND_HUMAN);
            l.setCreatedBy(r.getUserId());
            l.setCreatedAt(r.getCreatedAt() != null ? r.getCreatedAt() : now);
            l.setUpdatedAt(now);
            l = links.save(l);

            int order = 0;
            for (Long fid : parse(r.getFileIdsJson())) {
                if (fid == null) continue;
                EvidenceLinkTarget t = new EvidenceLinkTarget();
                t.setLinkId(l.getId());
                t.setFileId(fid);
                t.setLocatorHash(EvidenceLinkTarget.EMPTY_LOCATOR_HASH);
                t.setRelation(EvidenceLinkTarget.RELATION_SUPPORTS);
                t.setSortOrder(order++);
                t.setCreatedByKind(EvidenceLink.KIND_HUMAN);
                t.setCreatedBy(r.getUserId());
                t.setCreatedAt(l.getCreatedAt());
                targets.save(t);
            }
            lastMigrated++;
        }
        log.info("EvidenceLink 迁移完成: migrated={}, skipped={}", lastMigrated, lastSkipped);
    }

    private List<Long> parse(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<Long> ids = om.readValue(json, new TypeReference<List<Long>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    int lastMigrated() { return lastMigrated; }

    int lastSkipped() { return lastSkipped; }
}
