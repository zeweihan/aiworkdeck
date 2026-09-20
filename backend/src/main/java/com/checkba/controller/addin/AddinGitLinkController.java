// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.addin;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.controller.AuthController;
import com.checkba.model.entity.AddinGitRepoLink;
import com.checkba.repository.AddinGitRepoLinkRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.addin.GitProviderClient;
import com.checkba.service.addin.GitTokenCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 插件里关联 GitHub / Gitee 仓库（dev-board#720）：让不装桌面端的用户也有一个「权威源」
 * 可供 AI 参考。只读关联——AI 从不提交、不推送。
 *
 * <p>令牌只进不出：落库前经 {@link GitTokenCipher} 加密，任何响应里都只有 {@code tokenLast4}。
 * 保存前先真去仓库验一次并把分支解析成具体值，免得存下一个连不上的关联、等到某次对话里才发现。
 *
 * <p>信封与 {@link AddinPaneController} 一致：HTTP 恒 200，业务码在 {@code code} 里
 * （0 成功，4010 未登录，403 无权，400 入参/校验失败，503 服务器未配密钥），
 * {@code message} 是可直接显示给用户的一句话——插件端原样上浮。
 */
@RestController
@RequestMapping("/api/addin/git-links")
@RequiredArgsConstructor
@Slf4j
public class AddinGitLinkController {

    private final AddinGitRepoLinkRepository links;
    private final GitProviderClient client;
    private final GitTokenCipher cipher;
    private final ProjectMemberService members;

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return unauthenticated();
        if (projectId == null) {
            return error(400, LangText.of("请先选择项目", "Choose a project first"));
        }
        if (!members.hasReadPermission(projectId, userId)) {
            return error(403, LangText.of("没有这个项目的权限", "No access to this project"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AddinGitRepoLink link : links.findByUserIdAndCloudProjectId(userId, projectId)) {
            out.add(view(link));
        }
        return Map.of("code", 0, "links", out);
    }

    /** body {@code {projectId, url, branch, token}}；branch 与 token 都可以留空。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return unauthenticated();

        Long projectId = parseLong(body.get("projectId"));
        if (projectId == null) {
            return error(400, LangText.of("请先选择项目", "Choose a project first"));
        }
        if (!members.hasReadPermission(projectId, userId)) {
            return error(403, LangText.of("没有这个项目的权限", "No access to this project"));
        }

        String token = str(body.get("token"));
        // 公开仓库不需要令牌：没有令牌就没什么要加密的，密钥没配也照样能关联
        if (has(token) && !cipher.enabled()) {
            return error(503, LangText.of("服务器未配置 git 令牌密钥，无法保存访问令牌",
                    "This server has no git token secret configured, so the token cannot be stored"));
        }

        GitProviderClient.RepoRef parsed;
        try {
            parsed = GitProviderClient.parseUrl(str(body.get("url")), str(body.get("branch")));
        } catch (IllegalArgumentException e) {
            // 地址不合规就不出站：别把用户的令牌发到一个我们没打算支持的主机。
            // message 是 GitProviderClient 用 LangText 产的，已经是双语，原样上浮
            return error(400, e.getMessage());
        }

        GitProviderClient.RepoRef ref;
        try {
            ref = client.verify(parsed, token);
        } catch (GitProviderClient.GitAuthException e) {
            return error(400, LangText.of("仓库拒绝了这次访问：请检查访问令牌是否有效、是否有读取权限",
                    "The repository refused access: check that the token is valid and has read access"));
        } catch (FileNotFoundException e) {
            return error(400, LangText.of("找不到这个仓库或分支，请检查地址与分支名",
                    "No such repository or branch — check the URL and branch name"));
        } catch (IOException e) {
            log.warn("git 关联校验失败: provider={}, owner={}, repo={}, error={}",
                    parsed.provider(), parsed.owner(), parsed.repo(), e.getClass().getName());
            // 同上：GitProviderClient 的传输类 message 已是双语，且从不含 URL 与令牌
            return error(400, e.getMessage());
        }

        AddinGitRepoLink link = links.findByUserIdAndCloudProjectIdAndProviderAndOwnerAndRepo(
                        userId, projectId, ref.provider(), ref.owner(), ref.repo())
                .orElseGet(AddinGitRepoLink::new);
        if (link.getId() == null) {
            link.setUserId(userId);
            link.setCloudProjectId(projectId);
            link.setProvider(ref.provider());
            link.setOwner(ref.owner());
            link.setRepo(ref.repo());
            link.setCreatedAt(LocalDateTime.now());
        }
        link.setBranch(ref.branch());
        link.setTokenEnc(has(token) ? cipher.encrypt(token.trim()) : null);
        link.setTokenLast4(last4(token));
        link.setLastOkAt(LocalDateTime.now());
        // 重新填了令牌就是来修失效的：旧的失败原因必须清掉，不然面板永远挂着红字
        link.setLastError(null);
        AddinGitRepoLink saved = links.save(link);

        return Map.of("code", 0, "link", view(saved == null ? link : saved));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return unauthenticated();
        Optional<AddinGitRepoLink> row = links.findByIdAndUserId(id, userId);
        // 不是自己的行就什么都不做，也不告诉对方它存不存在
        row.ifPresent(links::delete);
        return Map.of("code", 0);
    }

    // ==================== 内部 ====================

    /** 对外视图：令牌密文绝不出现，只留末四位供用户认出「填的是哪一把」。 */
    private static Map<String, Object> view(AddinGitRepoLink link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", link.getId());
        m.put("provider", link.getProvider());
        m.put("owner", link.getOwner());
        m.put("repo", link.getRepo());
        m.put("branch", link.getBranch());
        m.put("tokenLast4", link.getTokenLast4());
        m.put("lastError", link.getLastError());
        return m;
    }

    private static Map<String, Object> unauthenticated() {
        return error(GlobalExceptionHandler.CODE_UNAUTHENTICATED, LangText.of("未登录", "Not signed in"));
    }

    private static Map<String, Object> error(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message == null || message.isBlank()
                ? LangText.of("操作失败，请稍后再试", "That did not work, please try again") : message);
        return m;
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    private static String last4(String token) {
        String t = token == null ? "" : token.trim();
        return t.length() >= 4 ? t.substring(t.length() - 4) : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

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
