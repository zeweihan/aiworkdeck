package com.checkba.controller;

import com.checkba.controller.AuthController;
import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.service.ContentSearchService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 全文搜索控制器
 */
@RestController
@RequestMapping("/api/projects/{projectId}/search")
@RequiredArgsConstructor
public class SearchController {

    private final ContentSearchService contentSearchService;
    private final ProjectMemberService projectMemberService;

    /**
     * 搜索项目内容
     * POST /api/projects/{projectId}/search
     */
    @PostMapping
    public SearchResult search(
            @PathVariable Long projectId,
            @RequestBody SearchRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        
        // 检查权限
        if (!projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该项目", "You don't have permission to access this project"));
        }
        
        return contentSearchService.searchContent(projectId, request);
    }

    private Long getUserIdFromSession(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId);
    }
}
