// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.insight.DocumentVisualReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/visual-review")
public class DocumentVisualReviewController {
    private final DocumentVisualReviewService service;
    @PostMapping
    public DocumentVisualReviewService.Result review(@PathVariable Long projectId,
            @RequestBody DocumentVisualReviewService.Request request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws Exception {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        return service.review(userId, projectId, request);
    }
}
