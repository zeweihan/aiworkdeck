package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.model.entity.AddinConvSyncOutbox;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.service.addin.AddinConvSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件对话镜像取件端点（dev-board#298）。鉴权与响应风格同 {@link MobileRelayController}：
 * X-Session-Id 收 awdt_ 设备令牌（或登录会话），裸数组/裸对象。
 */
@RestController
@RequestMapping("/api/mobile/conversations")
@RequiredArgsConstructor
public class MobileConvSyncController {

    private final AddinConvSyncService convSyncService;
    private final ProjectAiMessageRepository messageRepository;

    /**
     * 桌面端：本设备待导入的消息行（升序，单批 ≤200）。每行附会话当前标题——
     * 标题由 LLM 异步写在首条消息上、不产生新 outbox 行，取件时现查才拿得到最新值。
     */
    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox(
            @RequestParam("deviceId") String deviceId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        List<AddinConvSyncOutbox> rows = convSyncService.pendingForDevice(userId, deviceId);
        Map<String, String> titleByConversation = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AddinConvSyncOutbox row : rows) {
            String cid = row.getConversationId();
            if (!titleByConversation.containsKey(cid)) {
                titleByConversation.put(cid, messageRepository.findFirstByConversationId(cid)
                        .map(m -> m.getConversationTitle()).orElse(null));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("projectKey", row.getProjectKey());
            m.put("conversationId", cid);
            m.put("sourceMessageId", row.getSourceMessageId());
            m.put("role", row.getRole());
            m.put("content", row.getContent());
            m.put("displayContent", row.getDisplayContent());
            m.put("sourceChannel", row.getSourceChannel());
            m.put("title", titleByConversation.get(cid));
            m.put("messageCreatedAt", row.getMessageCreatedAt() != null ? row.getMessageCreatedAt().toString() : null);
            out.add(m);
        }
        return out;
    }

    /** 桌面端：确认导入，删点名的行。body {ids:[...]}。 */
    @PostMapping("/ack")
    public Map<String, Object> ack(
            @RequestBody Map<String, List<Long>> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        int deleted = convSyncService.ack(userId, body == null ? null : body.get("ids"));
        return Map.of("code", 0, "deleted", deleted);
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }
}
