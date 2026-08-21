package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧「文档-文件关联」端点（dev-board#102 起只读代理到 EvidenceLinkService，保留一个发版周期后删）。
 * GET 仍回旧 DocFileLinkResult 形状，让老版本桌面端点开 filelink 不断；POST 已 410——新链接一律走
 * /api/projects/{pid}/evidence-links。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/doc-links")
@RequiredArgsConstructor
public class DocFileLinkController {

    private final EvidenceLinkService evidenceLinkService;
    private final ProjectFileRepository projectFileRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrAppend() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "code", 1,
                "message", "doc-links 已下线，请改用 /api/projects/{projectId}/evidence-links"));
    }

    @GetMapping("/{linkKey}")
    public DocFileLinkResult get(@PathVariable Long projectId,
                                 @PathVariable String linkKey,
                                 @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        // 成员校验在 Service 内（hasReadPermission）
        LinkView v = evidenceLinkService.getByKey(userId, projectId, linkKey);

        DocFileLinkResult r = new DocFileLinkResult();
        r.setLinkKey(v.linkKey());
        r.setAnchorText(v.anchorText());
        ProjectFile doc = v.docFileId() == null ? null : projectFileRepository.findById(v.docFileId()).orElse(null);
        r.setDocWpsFileId(doc == null ? null : doc.getWpsFileId());

        Set<Long> fileIds = new LinkedHashSet<>();
        for (TargetView t : v.targets()) if (t.fileId() != null) fileIds.add(t.fileId());
        r.setFileIds(new ArrayList<>(fileIds));
        List<ProjectFile> files = new ArrayList<>();
        if (!fileIds.isEmpty()) {
            Map<Long, ProjectFile> byId = new java.util.HashMap<>();
            for (ProjectFile f : projectFileRepository.findAllById(fileIds)) byId.put(f.getId(), f);
            for (Long id : fileIds) if (byId.containsKey(id)) files.add(byId.get(id));
        }
        r.setFiles(files);
        return r;
    }

    /** 旧返回形状，逐字保留给老版本前端。 */
    @Data
    public static class DocFileLinkResult {
        private String linkKey;
        private String docWpsFileId;
        private String anchorText;
        private List<Long> fileIds;
        private List<ProjectFile> files;
    }
}
