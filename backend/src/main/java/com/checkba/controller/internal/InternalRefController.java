// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.internal;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.Project;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import com.checkba.service.file.ProjectFileTextExtractor;
import com.checkba.version.ProjectRepoService;
import com.checkba.version.VersionException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 官方案件库的内部参考口（dev-board#720，spec §7.1）。插件云后端（addin 实例）经 127.0.0.1
 * 问案件库（case 实例）：某个官网账号在案件库里能看到哪些文件、某一份的正文是什么。
 *
 * <p>两个实例是同一个 jar、两套库与用户表，同一个人在两边是两个本机 userId；跨实例身份键只有
 * {@code AccountBinding.externalAccountId}（官网账户 id）。
 *
 * <p>这条口子没有会话、只凭共享密钥说话，四道闸缺一不可，且一律回<b>裸 404</b>——与公网 nginx
 * 的 {@code ^~ /api/internal/} 兜底同一副面孔，不告诉扫描器"这里有个端点"：
 * <ol>
 *   <li>{@code ref.internal.serve} 未开（只有 case profile 开它，见下）；</li>
 *   <li>{@code ref.internal.secret} 未配置（国际站与自建服务器的默认态）；</li>
 *   <li>{@code X-Internal-Secret} 不等（常量时间比较，不给计时旁路）；</li>
 *   <li>来源不是回环地址。</li>
 * </ol>
 *
 * <p>第一道闸为什么必须单独存在：{@code ref.internal.secret} 是<b>一把密钥两个用途</b>——
 * 案件库拿它校验入站，插件云后端（addin 实例）拿它当出站头，部署文档要求两台配同一个值。
 * 若「配了密钥」就等于「本实例提供这条口子」，那么按文档给 addin 配上这个变量，
 * 等于在插件云后端里也打开了一对<b>不需要会话</b>、只按 {@code externalAccountId} 认人的
 * 读取端点，读的是 addin 自己的库；而 addin 侧的 nginx 并没有
 * {@code ^~ /api/internal/} 兜底，剩下的就只有回环判定一根绳。开关与密钥必须分开。
 *
 * <p>权限沿用案件库既有口径：项目成员可读、客户角色（CLIENT*）一概不可读，与
 * {@code VersionController.requireMember} 同一条线。判不过与"找不到"回同一句话，
 * 不回显别人案卷里的文件名。
 *
 * <p>红线：正文只在内存里过一遍，日志只记项目 id、条数与长度，绝不记正文与文件路径。
 */
@RestController
@RequestMapping("/api/internal/ref")
@Slf4j
public class InternalRefController {

    /** 一次 list 最多回多少条，避免把整座案件库倒给模型。 */
    static final int MAX_ENTRIES = 200;

    /** 文件树清单对律师不可见，对模型同样不可见（与 VersionController 各处的过滤同口径）。 */
    private static final String MANIFEST_PREFIX = ".awd/";

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    static final String NO_ACCESS = "在案件库里找不到这份文件，或你没有这份案卷的读取权限。";

    /** 本实例是否对外提供这条内部口。只有 case profile 打开（application-case.yml）。 */
    private final boolean serve;
    private final String secret;
    private final AccountBindingRepository bindings;
    private final ProjectService projectService;
    private final ProjectMemberService members;
    private final ProjectRepoService repoService;
    private final ProjectFileTextExtractor extractor;

    public InternalRefController(@Value("${ref.internal.serve:false}") boolean serve,
                                 @Value("${ref.internal.secret:}") String secret,
                                 AccountBindingRepository bindings,
                                 ProjectService projectService,
                                 ProjectMemberService members,
                                 ProjectRepoService repoService,
                                 ProjectFileTextExtractor extractor) {
        this.serve = serve;
        this.secret = secret == null ? "" : secret.trim();
        this.bindings = bindings;
        this.projectService = projectService;
        this.members = members;
        this.repoService = repoService;
        this.extractor = extractor;
    }

    /** body {@code {externalAccountId, keyword?}} → {@code {code:0, entries:[{remoteProjectId, projectName, path, name}]}}。 */
    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Secret", required = false) String header,
            HttpServletRequest request) {
        if (!allowed(header, request)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("entries", entriesFor(str(body, "externalAccountId"), str(body, "keyword")));
        return ResponseEntity.ok(out);
    }

    /** body {@code {externalAccountId, remoteProjectId, path}} → {@code {code:0, text}} 或 {@code {code:1, message}}。 */
    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> read(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Secret", required = false) String header,
            HttpServletRequest request) {
        if (!allowed(header, request)) {
            return ResponseEntity.notFound().build();
        }
        Long userId = userOf(str(body, "externalAccountId")).orElse(null);
        Long projectId = asLong(body == null ? null : body.get("remoteProjectId"));
        String path = str(body, "path");
        if (userId == null || projectId == null || path == null || path.isBlank()
                || path.startsWith(MANIFEST_PREFIX)
                || !members.hasReadPermission(projectId, userId)
                || members.isClient(projectId, userId)) {
            return ResponseEntity.ok(fail(NO_ACCESS));
        }
        byte[] bytes;
        try {
            bytes = repoService.readBlobAtCommit(projectId, repoService.mainBranch(), path);
        } catch (VersionException e) {
            // 50MB 闸是写给律师看的话术，透传；其余是仓库本身出问题，只说得出一句笼统的
            log.warn("案件库参考读取失败: project={}, userFacing={}", projectId, e.isUserFacing());
            return ResponseEntity.ok(fail(e.isUserFacing() ? e.getMessage()
                    : "案件库没能读出这份文件，请稍后再试。"));
        }
        if (bytes == null) {
            return ResponseEntity.ok(fail(NO_ACCESS));
        }
        String text;
        try {
            text = extractor.extractBytes(path, bytes);
        } catch (IOException e) {
            log.warn("案件库参考抽取失败: project={}, bytes={}", projectId, bytes.length);
            return ResponseEntity.ok(fail(e.getMessage() == null || e.getMessage().isBlank()
                    ? "这份文件没能抽出文字。" : e.getMessage()));
        }
        log.info("案件库参考读取: project={}, chars={}", projectId, text.length());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("text", text);
        return ResponseEntity.ok(out);
    }

    // ==================== 内部 ====================

    /**
     * 四道闸：本实例不提供这条口子、密钥未配置、密钥不符、来源不是回环地址。
     * 常量时间比较不是形式主义——这条口子可以被无限次重试，逐字节比较给的就是一个可测的旁路。
     */
    private boolean allowed(String header, HttpServletRequest request) {
        if (!serve || secret.isEmpty() || header == null) {
            return false;
        }
        if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                header.trim().getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        return loopback(request == null ? null : request.getRemoteAddr());
    }

    /**
     * 只认 IP 字面量：servlet 的 {@code getRemoteAddr()} 从来不回主机名，
     * 而拿主机名去解析会把判定交给可被投毒的 DNS。
     */
    static boolean loopback(String addr) {
        if (addr == null || addr.isBlank()) {
            return false;
        }
        String a = addr.trim().toLowerCase(Locale.ROOT);
        int zone = a.indexOf('%'); // IPv6 的 scope id
        if (zone > 0) {
            a = a.substring(0, zone);
        }
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        if (a.startsWith("::ffff:")) {
            a = a.substring("::ffff:".length());
        }
        return "::1".equals(a) || "0:0:0:0:0:0:0:1".equals(a) || IPV4_LOOPBACK.matcher(a).matches();
    }

    private List<Map<String, Object>> entriesFor(String externalAccountId, String keyword) {
        Long userId = userOf(externalAccountId).orElse(null);
        if (userId == null) {
            // 认不出这个账号不是错误：这个人只是还没在案件库登录过
            return List.of();
        }
        String needle = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project p : projectService.getUserProjects(userId)) {
            if (p == null || p.getId() == null || members.isClient(p.getId(), userId)) {
                continue;
            }
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
            collect(out, p, userId, needle);
        }
        log.info("案件库参考清单: userId={}, entries={}", userId, out.size());
        return out;
    }

    /** 单个案卷读不出来只跳过这一个，不让整份清单失败。 */
    private void collect(List<Map<String, Object>> out, Project p, Long userId, String needle) {
        long projectId = p.getId();
        try {
            if (!repoService.isInitialized(projectId)) {
                return;
            }
            String head = repoService.resolveRef(projectId, repoService.mainBranch());
            if (head == null) {
                return;
            }
            for (String path : repoService.listPaths(projectId, head)) {
                if (out.size() >= MAX_ENTRIES) {
                    return;
                }
                if (path == null || path.startsWith(MANIFEST_PREFIX)) {
                    continue;
                }
                String name = fileName(path);
                if (needle != null && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("remoteProjectId", projectId);
                e.put("projectName", p.getName());
                e.put("path", path);
                e.put("name", name);
                out.add(e);
            }
        } catch (RuntimeException e) {
            log.warn("案件库参考清单跳过一个案卷: project={}, userId={}, error={}",
                    projectId, userId, e.getClass().getName());
        }
    }

    private Optional<Long> userOf(String externalAccountId) {
        if (externalAccountId == null || externalAccountId.isBlank()) {
            return Optional.empty();
        }
        return bindings.findByExternalAccountId(externalAccountId.trim()).map(AccountBinding::getUserId);
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 1);
        out.put("message", message);
        return out;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
