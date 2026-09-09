// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.pack.ModelPresence;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
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
 * - GET  /optional-components 四个可选运行时组件的快照（登录，不发网络请求）
 * - POST /{id}/install      异步安装，幂等（admin）
 * - POST /{id}/upgrade      异步追新，有新版才换（admin）
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
    private final ModelPresence modelPresence;

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
            result.put("unpackedSize", info.unpackedSize());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    /**
     * 四个可选组件的一次性快照（设计 §3.2 / §4.1）。首次登录面板与「设置 - 组件管理」共用它。
     * <b>绝不发网络请求</b>：体积取内存/落盘快照，0 = 未知，前端要精确值再去打 /info。
     */
    @GetMapping("/optional-components")
    public ResponseEntity<Map<String, Object>> optionalComponents(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isLoggedIn(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        Map<String, Object> result = ok();
        result.put("components", optionalComponentViews(packService, modelPresence));
        return ResponseEntity.ok(result);
    }

    /** 包级可见（无修饰符）纯为单测：不起 Spring 上下文就能核对这份快照的形状。 */
    static List<Map<String, Object>> optionalComponentViews(NativePackService packs, ModelPresence models) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OptionalComponents.Entry e : OptionalComponents.ALL) {
            NativePackService.PackStatus st = packs.status(e.packId());
            NativePackService.Sizes sizes = packs.knownSizes(e.packId());
            Map<String, Object> m = new HashMap<>();
            m.put("packId", e.packId());
            m.put("service", e.service());
            m.put("state", st.getState());
            m.put("installed", NativePackService.STATE_READY.equals(st.getState()));
            m.put("installedVersion", st.getInstalledVersion());
            m.put("latestVersion", packs.knownLatestVersion(e.packId()));
            m.put("downloadBytes", sizes.downloadBytes());
            m.put("unpackedBytes", sizes.unpackedBytes());
            m.put("modelId", e.modelId());
            m.put("modelInstalled", models.installed(e.modelId()));
            m.put("modelBytes", e.modelBytes());
            m.put("featureKeys", e.featureKeys());
            out.add(m);
        }
        return out;
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

    /**
     * 手动追新（规范 §5.1）：拉一次 registry，有新版才走完整安装事务换上。
     * 与自动追新（{@code PackUpdater}）、手动安装共用同一把锁与同一条安装线程。
     */
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(
            @PathVariable("id") String packId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
        }
        try {
            // 检查同步、下载异步：前端要当场分清「已是最新」与「开始下载了」，
            // 拉不到清单也要当场拿到错误文案，而不是盯着一个不动的状态猜
            java.util.Optional<String> started = packService.startUpgrade(packId);
            Map<String, Object> result = ok();
            result.put("upgrading", started.isPresent());
            result.put("latestVersion", started.orElse(packService.knownLatestVersion(packId)));
            return ResponseEntity.ok(result);
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
        // 追新提示。latestVersion 是内存快照（最近一次真发过请求的路径写的），拿不到就是 null
        // ——列表端点绝不为它发网络请求：镜像不可达时 20s 超时 × 两个源会把整个广场拖死。
        m.put("latestVersion", packService.knownLatestVersion(packId));
        m.put("updateAvailable", packService.updateAvailable(packId));
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
