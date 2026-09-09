// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.completion.CompletionService;
import com.checkba.service.completion.CompletionService.*;
import com.checkba.service.insight.DocInsightViews.EntityView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/completion")
@RequiredArgsConstructor
public class CompletionController {
    private final CompletionService service;

    private Long uid(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        return userId;
    }

    @GetMapping
    public Candidates candidates(@PathVariable Long projectId,
                                  @RequestParam(required = false) String prefix,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.candidates(uid(sessionId), projectId, prefix);
    }

    @PostMapping("/learn")
    public LearnResult learn(@PathVariable Long projectId, @RequestBody LearnRequest request,
                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.learn(uid(sessionId), projectId, request);
    }

    @GetMapping("/entries/{entryId}")
    public EntityView detail(@PathVariable Long projectId, @PathVariable String entryId,
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.detail(uid(sessionId), projectId, entryId);
    }

    @DeleteMapping("/entries/{entryId}")
    public DeleteResult delete(@PathVariable Long projectId, @PathVariable String entryId,
                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.delete(uid(sessionId), projectId, entryId);
    }

    @DeleteMapping("/learned")
    public DeleteResult clear(@PathVariable Long projectId, @RequestParam String scope,
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.clear(uid(sessionId), projectId, scope);
    }

    public record LookupRequest(String kind, String text) {}

    /** 只有用户明确点击在线查询才调用；候选与学习两条链不经过这里。 */
    @PostMapping("/lookup")
    public EntityView lookup(@PathVariable Long projectId, @RequestBody LookupRequest request,
                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return service.lookupSelection(uid(sessionId), projectId,
                request == null ? null : request.kind(), request == null ? null : request.text());
    }
}
