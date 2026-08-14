package com.checkba.version.memory;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.MemoryRemote;
import com.checkba.repository.MemoryRemoteRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.version.VersionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆仓库远端配置的最小端点（spec Phase A 第 7 条「自填 remote」）。
 * repoKey 即 user-{userId}-memory / project-{projectId}-memory。
 *
 * 鉴权：user 仓只有本人能配置/查看（owner-only，与 Git 传输侧同口径）；
 * project 仓读=项目成员非 CLIENT，写=hasWritePermission。
 * 参数序 (projectId, userId)，两参同为 Long，写反能编译（地雷 #3）。
 * 响应封装照 VersionController：HTTP 一律 200，用 code 区分成败。
 */
@RestController
@RequestMapping("/api/memory-sync")
public class MemorySyncController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemorySyncController.class);

    private final MemorySyncService syncService;
    private final MemoryRemoteRepository remoteRepository;
    private final ProjectMemberService memberService;

    public MemorySyncController(MemorySyncService syncService,
                                MemoryRemoteRepository remoteRepository,
                                ProjectMemberService memberService) {
        this.syncService = syncService;
        this.remoteRepository = remoteRepository;
        this.memberService = memberService;
    }

    @GetMapping("/{repoKey}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String repoKey,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MemoryRealm realm = requireRealm(repoKey, sessionId, false);
        Map<String, Object> data = new HashMap<>();
        var cfg = remoteRepository.findByRepoKey(realm.repoKey()).orElse(null);
        data.put("configured", cfg != null);
        if (cfg != null) {
            data.put("url", cfg.getUrl());
            data.put("username", cfg.getUsername());
            // 凭据只写不读：只回打码后的形态（AccountService.mask 同款口径），明文永不回显
            data.put("secretMasked", maskSecret(cfg.getSecret()));
            data.put("pendingUpload", Boolean.TRUE.equals(cfg.getPendingUpload()));
            data.put("lastSyncAt", cfg.getLastSyncAt() == null ? null : cfg.getLastSyncAt().toString());
        }
        return ok(data);
    }

    /** 配置（或改写）远端：任意标准 Git URL + 凭据，本地保存。配置后立刻做一轮同步。 */
    @PostMapping("/{repoKey}/remote")
    public ResponseEntity<Map<String, Object>> setRemote(
            @PathVariable String repoKey,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MemoryRealm realm = requireRealm(repoKey, sessionId, true);
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            throw VersionException.userFacing(LangText.of("同步地址不能为空", "The sync address cannot be empty"));
        }
        MemoryRemote cfg = remoteRepository.findByRepoKey(realm.repoKey())
                .orElseGet(() -> {
                    MemoryRemote r = new MemoryRemote();
                    r.setRepoKey(realm.repoKey());
                    r.setCreatedAt(LocalDateTime.now());
                    return r;
                });
        cfg.setUrl(url.trim());
        cfg.setUsername(body.get("username"));
        // 凭据只写不读：status 只回打码值，界面上拿不到明文，重存配置时留空
        // 表示沿用已存令牌而不是清除（想清掉令牌走 DELETE 断开后重新配置）。
        String secret = body.get("secret");
        if (secret != null && !secret.isBlank()) {
            cfg.setSecret(secret);
        }
        remoteRepository.save(cfg);
        Map<String, Object> sync = syncService.syncNow(realm);
        Map<String, Object> data = new HashMap<>();
        data.put("configured", true);
        data.put("sync", sync);
        return ok(data);
    }

    @DeleteMapping("/{repoKey}/remote")
    public ResponseEntity<Map<String, Object>> removeRemote(
            @PathVariable String repoKey,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MemoryRealm realm = requireRealm(repoKey, sessionId, true);
        remoteRepository.findByRepoKey(realm.repoKey()).ifPresent(remoteRepository::delete);
        return ok(Map.of("configured", false));
    }

    /** 手动触发一轮同步。 */
    @PostMapping("/{repoKey}/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @PathVariable String repoKey,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MemoryRealm realm = requireRealm(repoKey, sessionId, true);
        return ok(syncService.syncNow(realm));
    }

    private MemoryRealm requireRealm(String repoKey, String sessionId, boolean write) {
        MemoryRealm realm = MemoryRealm.parse(repoKey);
        if (realm == null) {
            throw VersionException.userFacing(LangText.of("记忆仓库标识不正确", "Invalid memory repository identifier"));
        }
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException(LangText.of("无法确认当前用户身份", "Couldn't confirm the current user's identity"));
        }
        if (realm.kind() == MemoryRealm.Kind.USER) {
            if (realm.ownerId() != userId) {
                throw new IllegalArgumentException(LangText.of("无权访问这个记忆仓库", "You don't have access to this memory repository"));
            }
        } else {
            long projectId = realm.ownerId();
            if (!memberService.hasReadPermission(projectId, userId)
                    || memberService.isClient(projectId, userId)) {
                throw new IllegalArgumentException(LangText.of("无权访问该项目", "You don't have access to this project"));
            }
            if (write && !memberService.hasWritePermission(projectId, userId)) {
                throw new IllegalArgumentException(LangText.of("无权修改该项目", "You don't have permission to modify this project"));
            }
        }
        return realm;
    }

    /** 展示用掩码：只留末 4 位，够用户认出是哪枚令牌，又不泄露。 */
    static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.trim();
        if (s.length() <= 8) return "****";
        return "****" + s.substring(s.length() - 4);
    }

    @ExceptionHandler(VersionException.class)
    public ResponseEntity<Map<String, Object>> onVersionError(VersionException e) {
        log.warn("记忆同步操作失败", e);
        String message = e.isUserFacing() ? e.getMessage() : LangText.of("记忆同步失败，稍后会自动重试", "Memory sync failed — it will retry automatically later");
        return ResponseEntity.ok(Map.of("code", 1, "message", message));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
