// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.writing.WritingContextService;
import com.checkba.service.writing.WritingSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/writing")
@RequiredArgsConstructor
public class WritingSuggestionController {
    private final WritingContextService contexts;
    private final WritingSuggestionService suggestions;
    private Long uid(String sid) { Long uid=AuthController.getUserIdFromSession(sid); if(uid==null) throw new IllegalArgumentException("请先登录"); return uid; }
    @GetMapping("/settings") public WritingContextService.Settings settings(@PathVariable Long projectId,@RequestHeader(value="X-Session-Id",required=false) String sid) {return contexts.settings(uid(sid),projectId);}
    @PutMapping("/settings") public WritingContextService.Settings save(@PathVariable Long projectId,@RequestBody WritingContextService.SettingsInput body,@RequestHeader(value="X-Session-Id",required=false) String sid) {return contexts.saveSettings(uid(sid),projectId,body);}
    @PostMapping("/suggestions") public WritingSuggestionService.View start(@PathVariable Long projectId,@RequestBody WritingSuggestionService.Input body,@RequestHeader(value="X-Session-Id",required=false) String sid) {return suggestions.start(uid(sid),projectId,body);}
    @GetMapping("/suggestions/{id}") public WritingSuggestionService.View get(@PathVariable Long projectId,@PathVariable String id,@RequestHeader(value="X-Session-Id",required=false) String sid) {return suggestions.get(uid(sid),projectId,id);}
    @DeleteMapping("/suggestions/{id}") public WritingSuggestionService.View cancel(@PathVariable Long projectId,@PathVariable String id,@RequestHeader(value="X-Session-Id",required=false) String sid) {return suggestions.cancel(uid(sid),projectId,id);}
    @PostMapping("/suggestions/{id}/accept") public WritingSuggestionService.Accepted accept(@PathVariable Long projectId,@PathVariable String id,@RequestBody WritingSuggestionService.Accept body,@RequestHeader(value="X-Session-Id",required=false) String sid) {return suggestions.accept(uid(sid),projectId,id,body);}
}
