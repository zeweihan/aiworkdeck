// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectMemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件库侧的协作事件读写端点（spec 2026-09-14 §2.3）。
 *
 * <p>读：{@code GET /api/projects/{projectId}/collab-events} —— 权限只要求项目成员
 * （CLIENT 也可读，与只读成员同口径：谁能看这份案卷，谁就该知道它被谁动过）。
 *
 * <p>写：{@code POST} 只收 {@code PULLED} 一种。取回最新稿这件事只有客户端知道
 * （服务端那一侧就是一次普通的 upload-pack，跟日常 fetch 分不开），所以必须由它上报；
 * 其余类型服务端自己知道，开了口子就等于让任何成员往别人的历史里编事件。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/collab-events")
public class CollabEventController {

    private final CollabEventService eventService;
    private final ProjectMemberService memberService;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;

    public CollabEventController(CollabEventService eventService,
                                 ProjectMemberService memberService,
                                 UserRepository userRepository,
                                 DeviceTokenRepository deviceTokenRepository) {
        this.eventService = eventService;
        this.memberService = memberService;
        this.userRepository = userRepository;
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @GetMapping
    public Map<String, Object> list(
            @PathVariable Long projectId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "before", required = false) Long before,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long callerId = requireMember(projectId, sessionId);
        List<CollabEvent> rows = eventService.list(projectId,
                limit == null ? CollabEventService.DEFAULT_LIMIT : limit, before);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        Map<Long, User> cache = new HashMap<>();
        for (CollabEvent e : rows) {
            out.add(toDto(e, callerId, cache));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", Map.of("events", out));
        return result;
    }

    /**
     * 客户端上报「我取回了最新稿」。只收 PULLED，其余一律 400——**这是一个写别人历史
     * 的口子**，放开 kind 等于让任何有写权限的成员伪造「某某交了稿」。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> report(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String kind = body == null || body.get("kind") == null ? null : String.valueOf(body.get("kind"));
        if (!CollabEvent.Kind.PULLED.name().equals(kind)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 1,
                    "message", LangText.of("只接受取回最新稿的记录", "Only pull records are accepted")));
        }
        Object toSha = body.get("toSha");
        eventService.record(CollabEvent.Kind.PULLED, projectId, userId, null,
                null, toSha == null ? null : String.valueOf(toSha), null, null, null);
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of()));
    }

    // ==================== 内部 ====================

    private Long requireMember(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null || !memberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of(
                    "无权访问该项目", "You don't have access to this project"));
        }
        return userId;
    }

    private Map<String, Object> toDto(CollabEvent e, Long callerId, Map<Long, User> cache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("kind", e.getKind());
        m.put("actor", person(e.getActorUserId(), cache));
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("tokenId", e.getTokenId());
        // 设备名只对**事件本人**下发：界面唯一用到它的地方就是「你（{设备名}）」那一句
        // （同事那一侧显示的是展示名）。把别人机器的主机名摊给全项目成员，是白给的信息。
        device.put("name", e.getActorUserId() != null && e.getActorUserId().equals(callerId)
                ? deviceName(e.getTokenId()) : null);
        m.put("device", device);
        m.put("fromSha", e.getFromSha());
        m.put("toSha", e.getToSha());
        m.put("commitCount", e.getCommitCount());
        m.put("target", e.getTargetUserId() == null ? null : person(e.getTargetUserId(), cache));
        m.put("detail", CollabEventService.parseDetail(e.getDetail()));
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private String deviceName(Long tokenId) {
        if (tokenId == null) return null;
        return deviceTokenRepository.findById(tokenId)
                .map(com.checkba.model.entity.DeviceToken::getName).orElse(null);
    }

    /**
     * 事件里的一个人。口径与参与人列表逐字一致（展示名本地化 + 头像三级回落），
     * username 保留一版给老客户端，界面不读它（spec 2026-09-10 §4）。
     */
    private Map<String, Object> person(Long userId, Map<Long, User> cache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        if (userId == null) return m;
        User u = cache.computeIfAbsent(userId, id -> userRepository.findById(id).orElse(null));
        if (u == null) return m;
        m.put("username", u.getUsername());
        m.put("displayName", LocalIdentityService.displayNameOf(u.getDisplayName()));
        m.put("avatarUrl", memberService.avatarUrlFor(u));
        return m;
    }
}
