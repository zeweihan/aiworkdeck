package com.checkba.controller;

import com.checkba.service.DocFileLinkService;
import com.checkba.service.ProjectMemberService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/doc-links")
@RequiredArgsConstructor
public class DocFileLinkController {

    private final DocFileLinkService docFileLinkService;
    private final ProjectMemberService projectMemberService;

    @PostMapping
    public DocFileLinkService.DocFileLinkResult createOrAppend(
            @PathVariable Long projectId,
            @RequestBody CreateOrAppendRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId
    ) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        requireProjectMember(projectId, userId);

        return docFileLinkService.createOrAppend(
                userId,
                projectId,
                request.getDocWpsFileId(),
                request.getLinkKey(),
                request.getAnchorText(),
                request.getRangeStart(),
                request.getRangeEnd(),
                request.getFileIds()
        );
    }

    @GetMapping("/{linkKey}")
    public DocFileLinkService.DocFileLinkResult get(
            @PathVariable Long projectId,
            @PathVariable String linkKey,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId
    ) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        requireProjectMember(projectId, userId);
        return docFileLinkService.getByKey(userId, projectId, linkKey);
    }

    private Long getUserIdFromSession(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId);
    }

    /**
     * 只登录不够：返回体里带的是该项目的 ProjectFile 实体（文件名/存储路径/wpsFileId），
     * 不校成员就等于让任何账号按 id 枚举别家项目的文件清单。
     */
    private void requireProjectMember(Long projectId, Long userId) {
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权限访问该项目");
        }
    }

    public static class CreateOrAppendRequest {
        private String docWpsFileId;
        private String linkKey;
        private String anchorText;
        private Integer rangeStart;
        private Integer rangeEnd;
        private List<Long> fileIds;

        public String getDocWpsFileId() { return docWpsFileId; }
        public void setDocWpsFileId(String docWpsFileId) { this.docWpsFileId = docWpsFileId; }
        public String getLinkKey() { return linkKey; }
        public void setLinkKey(String linkKey) { this.linkKey = linkKey; }
        public String getAnchorText() { return anchorText; }
        public void setAnchorText(String anchorText) { this.anchorText = anchorText; }
        public Integer getRangeStart() { return rangeStart; }
        public void setRangeStart(Integer rangeStart) { this.rangeStart = rangeStart; }
        public Integer getRangeEnd() { return rangeEnd; }
        public void setRangeEnd(Integer rangeEnd) { this.rangeEnd = rangeEnd; }
        public List<Long> getFileIds() { return fileIds; }
        public void setFileIds(List<Long> fileIds) { this.fileIds = fileIds; }
    }
}


