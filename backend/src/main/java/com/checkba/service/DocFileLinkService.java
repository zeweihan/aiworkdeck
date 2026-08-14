package com.checkba.service;

import com.checkba.model.entity.DocFileLink;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DocFileLinkRepository;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DocFileLinkService {

    private final DocFileLinkRepository docFileLinkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class DocFileLinkResult {
        private String linkKey;
        private String docWpsFileId;
        private String anchorText;
        private List<Long> fileIds;
        private List<ProjectFile> files;

        public String getLinkKey() { return linkKey; }
        public void setLinkKey(String linkKey) { this.linkKey = linkKey; }
        public String getDocWpsFileId() { return docWpsFileId; }
        public void setDocWpsFileId(String docWpsFileId) { this.docWpsFileId = docWpsFileId; }
        public String getAnchorText() { return anchorText; }
        public void setAnchorText(String anchorText) { this.anchorText = anchorText; }
        public List<Long> getFileIds() { return fileIds; }
        public void setFileIds(List<Long> fileIds) { this.fileIds = fileIds; }
        public List<ProjectFile> getFiles() { return files; }
        public void setFiles(List<ProjectFile> files) { this.files = files; }
    }

    @Transactional
    public DocFileLinkResult createOrAppend(Long userId,
                                           Long projectId,
                                           String docWpsFileId,
                                           String linkKey,
                                           String anchorText,
                                           Integer rangeStart,
                                           Integer rangeEnd,
                                           List<Long> fileIds) {
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (projectId == null) throw new IllegalArgumentException(LangText.of("项目ID不能为空", "Project ID must not be empty"));
        if (!StringUtils.hasText(docWpsFileId)) throw new IllegalArgumentException(LangText.of("docWpsFileId 不能为空", "docWpsFileId must not be empty"));
        if (fileIds == null || fileIds.isEmpty()) throw new IllegalArgumentException(LangText.of("fileIds 不能为空", "fileIds must not be empty"));

        // 归属校验：只接受属于本项目的 fileId，防止把他人项目文件 ID 存入链接后经 getByKey
        // 读回其文件名/存储路径/wpsFileId 等元数据（IDOR）
        for (Long fid : fileIds) {
            if (fid == null) continue;
            ProjectFile pf = projectFileRepository.findById(fid).orElse(null);
            if (pf == null || !projectId.equals(pf.getProjectId())) {
                throw new IllegalArgumentException(LangText.of("文件不属于该项目: ", "This file does not belong to this project: ") + fid);
            }
        }

        String key = StringUtils.hasText(linkKey) ? linkKey.trim() : ("lk_" + UUID.randomUUID());
        DocFileLink link = docFileLinkRepository.findByProjectIdAndLinkKey(projectId, key).orElse(null);
        if (link == null) {
            link = new DocFileLink();
            link.setProjectId(projectId);
            link.setUserId(userId);
            link.setDocWpsFileId(docWpsFileId.trim());
            link.setLinkKey(key);
            link.setAnchorText(StringUtils.hasText(anchorText) ? anchorText.trim() : null);
            link.setRangeStart(rangeStart);
            link.setRangeEnd(rangeEnd);
            link.setCreatedAt(LocalDateTime.now());
        } else {
            // 只允许创建者修改（避免跨用户污染）
            if (!Objects.equals(link.getUserId(), userId)) {
                throw new IllegalArgumentException(LangText.of("无权限修改该链接", "You do not have permission to modify this link"));
            }
            // 文档 ID 变更（理论不应发生），保留原值，避免把同 key 混到另一文档
        }

        // 合并 fileIds（不去重逻辑？此处业务上应去重避免列表膨胀）
        List<Long> merged = new ArrayList<>();
        try {
            if (StringUtils.hasText(link.getFileIdsJson())) {
                merged.addAll(objectMapper.readValue(link.getFileIdsJson(), new TypeReference<List<Long>>() {}));
            }
        } catch (Exception ignore) {
            // ignore
        }
        for (Long fid : fileIds) {
            if (fid == null) continue;
            if (!merged.contains(fid)) merged.add(fid);
        }
        link.setFileIdsJson(writeJson(merged));
        link.setUpdatedAt(LocalDateTime.now());

        DocFileLink saved = docFileLinkRepository.save(link);
        return buildResult(saved);
    }

    @Transactional(readOnly = true)
    public DocFileLinkResult getByKey(Long userId, Long projectId, String linkKey) {
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (projectId == null) throw new IllegalArgumentException(LangText.of("项目ID不能为空", "Project ID must not be empty"));
        if (!StringUtils.hasText(linkKey)) throw new IllegalArgumentException(LangText.of("linkKey 不能为空", "linkKey must not be empty"));

        DocFileLink link = docFileLinkRepository.findByProjectIdAndLinkKey(projectId, linkKey.trim())
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("链接不存在", "Link not found")));

        // 只允许创建者读取（后续可扩展为项目内共享）
        if (!Objects.equals(link.getUserId(), userId)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该链接", "You do not have access to this link"));
        }
        return buildResult(link);
    }

    private DocFileLinkResult buildResult(DocFileLink link) {
        DocFileLinkResult r = new DocFileLinkResult();
        r.setLinkKey(link.getLinkKey());
        r.setDocWpsFileId(link.getDocWpsFileId());
        r.setAnchorText(link.getAnchorText());

        List<Long> fileIds = new ArrayList<>();
        try {
            if (StringUtils.hasText(link.getFileIdsJson())) {
                fileIds = objectMapper.readValue(link.getFileIdsJson(), new TypeReference<List<Long>>() {});
            }
        } catch (Exception ignore) {
            // ignore
        }
        r.setFileIds(fileIds);

        // 返回文件元信息，便于前端弹窗展示
        List<ProjectFile> files = new ArrayList<>();
        for (Long fid : fileIds) {
            if (fid == null) continue;
            projectFileRepository.findById(fid).ifPresent(files::add);
        }
        r.setFiles(files);
        return r;
    }

    private String writeJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }
}


