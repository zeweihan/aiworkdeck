package com.checkba.controller;

import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.pack.NativePackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 原生资源包接口（规范见 docs/NATIVE_PACK_DISTRIBUTION.md §4.3）：
 * - GET  /list              已知 pack 的状态（登录）
 * - GET  /{id}/status       单个 pack 的状态（登录）
 * - GET  /{id}/info         最新版本与本平台下载体积（登录，manifest 缓存 5 分钟）
 * - POST /{id}/install      异步安装，幂等（admin）
 * - POST /{id}/uninstall    卸载（admin）
 *
 * 鉴权模式与 SkillController 一致：X-Session-Id → userId → AdminAccessService
 * （桌面单机全员管理员）。
 */
@RestController
@RequestMapping("/api/packs")
@RequiredArgsConstructor
public class PackController {

    private static final Pattern PACK_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    private final NativePackService packService;
    private final SkillRegistry skillRegistry;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isLoggedIn(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        List<Map<String, Object>> packs = new ArrayList<>();
        for (String id : knownPackIds()) {
            packs.add(statusOf(id));
        }
        Map<String, Object> result = ok();
        result.put("packs", packs);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable("id") String packId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isLoggedIn(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        if (!PACK_ID.matcher(packId == null ? "" : packId).matches()) {
            return ResponseEntity.ok(error(LangText.of("非法资源包 id: ", "Invalid pack ID: ") + packId));
        }
        Map<String, Object> result = ok();
        result.put("status", statusOf(packId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/info")
    public ResponseEntity<Map<String, Object>> info(
            @PathVariable("id") String packId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isLoggedIn(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        try {
            NativePackService.PackInfo info = packService.info(packId);
            Map<String, Object> result = ok();
            result.put("latestVersion", info.latestVersion());
            result.put("totalSize", info.totalSize());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<Map<String, Object>> install(
            @PathVariable("id") String packId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            packService.installAsync(packId);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/uninstall")
    public ResponseEntity<Map<String, Object>> uninstall(
            @PathVariable("id") String packId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            packService.uninstall(packId);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /** 已知 pack = 本地已装 ∪ 已注册 skill 声明的 requires_pack */
    private Set<String> knownPackIds() {
        Set<String> ids = new LinkedHashSet<>(packService.installedPackIds());
        for (SkillDefinition skill : skillRegistry.getSkills()) {
            String packId = skill.getRequiresPack();
            if (packId != null && !packId.isBlank() && PACK_ID.matcher(packId).matches()) {
                ids.add(packId);
            }
        }
        return ids;
    }

    private Map<String, Object> statusOf(String packId) {
        NativePackService.PackStatus st = packService.status(packId);
        Map<String, Object> m = new HashMap<>();
        m.put("id", packId);
        m.put("state", st.getState());
        m.put("installedVersion", st.getInstalledVersion());
        m.put("bytesDownloaded", st.getBytesDownloaded());
        m.put("bytesTotal", st.getBytesTotal());
        m.put("error", st.getError());
        return m;
    }

    private boolean isLoggedIn(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId) != null;
    }

    private boolean isAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(adminAccessService::isAdmin)
                .orElse(false);
    }

    private Map<String, Object> ok() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
