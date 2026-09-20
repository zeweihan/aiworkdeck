// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

import com.checkba.service.LangText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub / Gitee 的只读出站客户端（dev-board#720，spec §7.2）。
 *
 * <p><b>只读</b>：列文件、读文件、验一次连通性，没有任何写接口——D 决策定的，
 * 别顺手加 commit / push。
 *
 * <p>凭据的走法两家不同：GitHub 用 {@code Authorization: Bearer}，Gitee v5 用
 * {@code access_token} 查询参数（v5 的文档口径）。<b>因此这个类里任何报错都不许把 URL
 * 或上游响应体拼进 message</b>——Gitee 的令牌就在查询串里，拼一次就等于写进一次日志。
 * 日志只记 provider / owner / repo / 状态码。
 *
 * <p>超时与形态照 {@link com.checkba.service.ai.ref.CaseRefClient}：固定 HTTP/1.1、不跟随重定向、
 * 不重试——律师就在窗格前面等着。
 *
 * <p><b>文案</b>：会经 {@code e.getMessage()} 上浮到插件面板的 message 一律走 {@link LangText}
 * （控制器的两条兜底分支原样显示它），常量写死等于英文用户拿到中文。401/403 与 404 两条例外：
 * 上层（控制器、{@link com.checkba.service.ai.ref.GitProviderSource}）各有自己的双语文案，
 * 这里的 message 到不了用户面前。
 */
@Component
@Slf4j
public class GitProviderClient {

    public static final String GITHUB = "github";
    public static final String GITEE = "gitee";

    /** 参考材料的单文件字节上限，与 ProjectFileTextExtractor.MAX_BYTES 同一个数。 */
    static final long MAX_BYTES = 50L * 1024 * 1024;

    static String tooLarge() {
        return LangText.of("文件超过 50MB，暂不支持作为参考材料读取",
                "This file is larger than 50MB, which is too large to use as reference material");
    }

    static String onlyHosts() {
        return LangText.of("只支持 GitHub 与 Gitee 仓库", "Only GitHub and Gitee repositories are supported");
    }

    static String badUrl() {
        return LangText.of("仓库地址格式不对，例如 https://github.com/用户名/仓库名",
                "That is not a repository URL, for example https://github.com/owner/repo");
    }

    /** 路径段不合法（{@code .} / {@code ..} / 空段），见 {@link #encodePath}。 */
    static String badPath() {
        return LangText.of("路径不合法", "Invalid path");
    }

    /** 目录树响应的读取上限：一个超大仓库的 recursive 树也就几 MB，16MB 足够又不至于被喂爆。 */
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern SSH = Pattern.compile("^git@([^:/]+):(.+)$");
    private static final Pattern HTTPS = Pattern.compile("^https?://([^/]+)/(.*)$", Pattern.CASE_INSENSITIVE);

    private final String githubApi;
    private final String giteeApi;
    private final long maxBytes;
    private final ObjectMapper om = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    public GitProviderClient(@Value("${addin.git.github-api:https://api.github.com}") String githubApi,
                             @Value("${addin.git.gitee-api:https://gitee.com/api/v5}") String giteeApi) {
        this(githubApi, giteeApi, MAX_BYTES);
    }

    /** 测试用：把字节上限调小，免得为了验一条守卫真往回环里灌 50MB。 */
    GitProviderClient(String githubApi, String giteeApi, long maxBytes) {
        this.githubApi = trimTrailingSlash(githubApi);
        this.giteeApi = trimTrailingSlash(giteeApi);
        this.maxBytes = maxBytes;
    }

    /**
     * 仓库地址 → {@link RepoRef}。纯函数，不出网。
     *
     * <p>接受 {@code https://github.com/o/r(.git)}、{@code https://gitee.com/o/r(.git)}、
     * {@code git@github.com:o/r.git}；别家主机当场拒绝——用户的 GitLab 令牌不该被发到一个
     * 我们没打算支持的地方。
     *
     * @param branch 分支，可为空；<b>留空时返回的 branch 是 null</b>，由 {@link #verify} 去问仓库的
     *               默认分支。界面上写的就是「留空为默认」，这里钉死成 master 会让 2020 年之后
     *               建的 GitHub 仓库（默认分支 main）一关联就失败。
     */
    public static RepoRef parseUrl(String url, String branch) {
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("请填写仓库地址", "Enter the repository URL"));
        }
        String host;
        String path;
        Matcher ssh = SSH.matcher(raw);
        Matcher https = HTTPS.matcher(raw);
        if (ssh.matches()) {
            host = ssh.group(1);
            path = ssh.group(2);
        } else if (https.matches()) {
            host = https.group(1);
            path = https.group(2);
        } else {
            throw new IllegalArgumentException(badUrl());
        }
        host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        int at = host.indexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        String provider = switch (host) {
            case "github.com" -> GITHUB;
            case "gitee.com" -> GITEE;
            default -> throw new IllegalArgumentException(onlyHosts());
        };
        String[] segments = path.replaceAll("/+$", "").replaceAll("\\.git$", "").split("/");
        List<String> parts = new ArrayList<>();
        for (String s : segments) {
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        if (parts.size() != 2) {
            throw new IllegalArgumentException(badUrl());
        }
        String trimmedBranch = branch == null || branch.isBlank() ? null : branch.trim();
        return new RepoRef(provider, parts.get(0), parts.get(1), trimmedBranch);
    }

    /**
     * 关联前验一次：确认这个仓库（以及用户填的那个分支）连得上，并把分支解析成具体值。
     *
     * @return 分支已解析好的 {@link RepoRef}——落库存的就是它，之后读取不再二次解析
     */
    public RepoRef verify(RepoRef ref, String token) throws IOException {
        if (ref.branch() == null || ref.branch().isBlank()) {
            JsonNode repo = json(ref, "/repos/" + encodePath(ref.owner()) + "/" + encodePath(ref.repo()),
                    null, token);
            String def = repo.path("default_branch").asText(null);
            if (def == null || def.isBlank()) {
                throw new IOException(LangText.of("没能读出这个仓库的默认分支，请在分支里填写具体分支名",
                        "Could not read this repository's default branch — enter a branch name"));
            }
            return new RepoRef(ref.provider(), ref.owner(), ref.repo(), def);
        }
        // 用户自己填了分支：真去问那个分支存不存在，否则填错分支要等到某次对话里读文件才暴露
        listPaths(ref, token);
        return ref;
    }

    /** 该分支上的全部文件路径（不含目录）。 */
    public List<String> listPaths(RepoRef ref, String token) throws IOException {
        JsonNode tree = json(ref, "/repos/" + encodePath(ref.owner()) + "/" + encodePath(ref.repo())
                + "/git/trees/" + encodePath(ref.branch()), "recursive=1", token);
        List<String> out = new ArrayList<>();
        for (JsonNode n : tree.path("tree")) {
            if ("blob".equals(n.path("type").asText())) {
                String p = n.path("path").asText(null);
                if (p != null && !p.isBlank()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 该分支上那个文件的原始字节。 */
    public byte[] readFile(RepoRef ref, String token, String path) throws IOException {
        String owner = encodePath(ref.owner());
        String repo = encodePath(ref.repo());
        String encoded = encodePath(path);
        String route = GITHUB.equals(ref.provider())
                ? "/repos/" + owner + "/" + repo + "/contents/" + encoded
                : "/repos/" + owner + "/" + repo + "/raw/" + encoded;
        HttpRequest.Builder req = request(ref, route, "ref=" + encodeValue(ref.branch()), token);
        if (GITHUB.equals(ref.provider())) {
            // raw 媒体类型：直接拿字节，不必为大于 1MB 的文件再走一趟 blob API
            req.header("Accept", "application/vnd.github.raw");
        }
        byte[] bytes = body(ref, send(ref, route, req), (int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
        if (bytes.length > maxBytes) {
            throw new IOException(tooLarge());
        }
        return bytes;
    }

    // ==================== 内部 ====================

    private JsonNode json(RepoRef ref, String route, String query, String token) throws IOException {
        byte[] raw = body(ref, send(ref, route, request(ref, route, query, token)), MAX_JSON_BYTES);
        try {
            return om.readTree(raw);
        } catch (Exception e) {
            // Jackson 的报错里带着出错位置附近的原文，不能往上抛
            throw new IOException(LangText.of(label(ref.provider()) + "的响应无法解析",
                    "Could not parse the response from " + label(ref.provider())));
        }
    }

    /** 读响应体。读到一半断了也自己造 message：底层异常的 message 里可能有完整 URL。 */
    private byte[] body(RepoRef ref, InputStream in, int limit) throws IOException {
        try (InputStream stream = in) {
            return stream.readNBytes(limit);
        } catch (IOException e) {
            log.warn("git 响应读取失败: provider={}, owner={}, repo={}, error={}",
                    ref.provider(), ref.owner(), ref.repo(), e.getClass().getName());
            throw new IOException(LangText.of("读取 " + label(ref.provider()) + " 的响应失败，请稍后再试",
                    "Reading the response from " + label(ref.provider()) + " failed, please try again later"));
        }
    }

    private HttpRequest.Builder request(RepoRef ref, String route, String query, String token) {
        String base = GITHUB.equals(ref.provider()) ? githubApi : giteeApi;
        StringBuilder url = new StringBuilder(base).append(route);
        String q = query == null ? "" : query;
        if (GITEE.equals(ref.provider()) && token != null && !token.isBlank()) {
            q = q.isEmpty() ? "access_token=" + encodeValue(token.trim())
                    : q + "&access_token=" + encodeValue(token.trim());
        }
        if (!q.isEmpty()) {
            url.append('?').append(q);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(TIMEOUT)
                .GET();
        if (GITHUB.equals(ref.provider()) && token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token.trim());
            b.header("X-GitHub-Api-Version", "2022-11-28");
        }
        return b;
    }

    /**
     * 发一次请求并按状态码分流。
     *
     * <p>报错 message 一律自己造：上游的响应体与 URL（Gitee 的令牌在里面）都不进 message、不进日志。
     */
    private InputStream send(RepoRef ref, String route, HttpRequest.Builder req) throws IOException {
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(LangText.of(label(ref.provider()) + "请求被中断",
                    "The request to " + label(ref.provider()) + " was interrupted"));
        } catch (IOException e) {
            // cause 不带：它的 message 里可能有完整 URL（含 Gitee 的令牌）
            log.warn("git 请求失败: provider={}, owner={}, repo={}, error={}",
                    ref.provider(), ref.owner(), ref.repo(), e.getClass().getName());
            throw new IOException(LangText.of("连不上 " + label(ref.provider()) + "，请稍后再试",
                    "Could not reach " + label(ref.provider()) + ", please try again later"));
        }
        int status = resp.statusCode();
        if (status == 200) {
            long declared = resp.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                close(resp.body());
                throw new IOException(tooLarge());
            }
            return resp.body();
        }
        close(resp.body());
        log.warn("git 请求失败: provider={}, owner={}, repo={}, route={}, status={}",
                ref.provider(), ref.owner(), ref.repo(), route, status);
        if (status == 401 || status == 403) {
            throw new GitAuthException(label(ref.provider()) + "拒绝了这次访问（" + status + "）");
        }
        if (status == 404) {
            throw new FileNotFoundException(label(ref.provider()) + "上找不到这个仓库、分支或文件");
        }
        throw new IOException(LangText.of(label(ref.provider()) + "返回 " + status,
                label(ref.provider()) + " returned " + status));
    }

    private static void close(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignore) {
            // 连接会被 HttpClient 回收，关不掉不影响结果
        }
    }

    private static String label(String provider) {
        return GITHUB.equals(provider) ? "GitHub" : "Gitee";
    }

    private static String trimTrailingSlash(String s) {
        String v = s == null ? "" : s.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    /**
     * 逐段编码，保留 {@code /}：分支名与文件路径里都可能有中文、空格。
     *
     * <p><b>先挡 {@code .} / {@code ..} / 空段，再编码。</b>URLEncoder 不碰点号，
     * {@code ../../../../user/repos} 会原封不动留在路径里，而 GitHub / Gitee 的路由会像任何
     * HTTP 路由一样把它归一化——于是一次带着用户令牌的 GET 就离开了这个仓库的路由（{@code /user/repos}、
     * 另一个私有仓库的 contents），响应还会顺着 ref_read 回到对话里。这条守卫在出站之前，
     * 一个字节都不发。
     *
     * <p>path 那一半来自模型给的 {@code git:<linkId>:<path>}，是它读到的任何文本
     * （关联仓库里的一个文件、用户贴进来的一段话）都能影响的；branch 是用户在面板里填的。
     * 两者都当不可信输入看——这个功能对外只承诺「读你指定的那个仓库」。
     *
     * @throws FileNotFoundException 段不合法。三个调用方都 {@code throws IOException}，
     *         对上层与「仓库里没有这个文件」走同一条路，不给探路的人多余的信号
     */
    private static String encodePath(String path) throws FileNotFoundException {
        String[] segments = (path == null ? "" : path).split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                // 路径本身不进日志：它是用户仓库里的文件名
                log.warn("git 路径被拒绝：出现了 . / .. 或空的目录名");
                throw new FileNotFoundException(badPath());
            }
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodeValue(segment));
        }
        return sb.toString();
    }

    private static String encodeValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 一个仓库的坐标。
     *
     * @param branch 具体分支；{@link #parseUrl} 在用户留空时给 null，{@link #verify} 之后一定有值
     */
    public record RepoRef(String provider, String owner, String repo, String branch) {
    }

    /** 401 / 403：令牌无效、过期或权限不够——要用户去重填，不是稍后重试能好的。 */
    public static class GitAuthException extends IOException {
        public GitAuthException(String message) {
            super(message);
        }
    }
}
