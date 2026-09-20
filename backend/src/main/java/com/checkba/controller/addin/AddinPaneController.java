// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.addin;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.controller.AuthController;
import com.checkba.service.LangText;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.addin.PaneRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 插件窗格心跳与告别（dev-board#717）：维护 {@link PaneRegistry}，
 * 供跨文档读写找到「同一账号下其他开着的文档窗格」。
 */
@RestController
@RequestMapping("/api/addin/panes")
@RequiredArgsConstructor
@Slf4j
public class AddinPaneController {

    private final PaneRegistry registry;
    private final ObjectMapper mapper;
    private final ProjectAiMessageService messageService;

    /**
     * body {@code {paneId, host, family, docName, projectId, conversationId}}，窗格每 30 秒一次。
     *
     * <p><b>会话 id 不信任请求体</b>（与 {@code OfficeResultController} 同一条线）：登记簿是
     * 跨窗格下发链路上唯一一处 conversationId 来自客户端的地方，而
     * {@code OfficeBridgeService.executeOnPane} 只看这条会话「连着没有」就往它推 client_action。
     * 不校验的话，任何登录用户把别人的 conversationId 登记到自己名下，就能用 ref_read / ref_edit
     * 读写别人开着的文档（结果回传那一闸拦不住：受害者自己的窗格是合法投递者）。
     */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return unauthenticated();
        String conversationId = str(body.get("conversationId"));
        if (conversationId != null && !conversationId.isBlank()
                && !messageService.canUseConversation(conversationId, userId)) {
            // 会话 id 本身也是凭据，不进日志
            log.warn("拒绝窗格心跳：会话不属于该账号 userId={}", userId);
            return Map.of("code", 1,
                    "message", LangText.of("无权使用该会话", "Not allowed to use this conversation"));
        }
        registry.heartbeat(userId, new PaneRegistry.PaneInfo(
                str(body.get("paneId")), userId, str(body.get("host")), str(body.get("family")),
                str(body.get("docName")), parseLong(body.get("projectId")),
                conversationId, 0));
        return Map.of("code", 0);
    }

    /**
     * 窗格卸载时的告别。sendBeacon 只能发 text/plain，且带不了自定义请求头，
     * 所以会话令牌可以放在 body.token 里；有请求头时以请求头为准。
     */
    @PostMapping(value = "/bye", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public Map<String, Object> bye(@RequestBody String raw,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Map<?, ?> body;
        try {
            body = mapper.readValue(raw, Map.class);
        } catch (Exception e) {
            body = null;
        }
        if (body == null) {
            return Map.of("code", 1, "message", LangText.of("请求格式错误", "Malformed request"));
        }
        String token = sessionId != null ? sessionId : str(body.get("token"));
        Long userId = AuthController.getUserIdFromSession(token);
        if (userId == null) return unauthenticated();
        registry.bye(userId, str(body.get("paneId")));
        return Map.of("code", 0);
    }

    private static Map<String, Object> unauthenticated() {
        return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED,
                "message", LangText.of("未登录", "Not signed in"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 项目 id 缺失或解析不了都当「没带项目」：心跳不能因为这一项坏掉而整条失败。 */
    private static Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
