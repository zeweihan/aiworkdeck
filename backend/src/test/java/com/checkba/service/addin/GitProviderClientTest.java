// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.addin.GitProviderClient.RepoRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GitHub / Gitee 只读出站客户端（dev-board#720，spec §7.2）。
 *
 * <p>桩服务用 JDK 自带的 {@link HttpServer}：判据全在 HTTP 层（路径、请求头、查询参数、
 * 状态码分流），用 mock 绕过去等于没测（与 CaseRefClientTest 同款）。
 *
 * <p>本类真正护的几条：
 * <ol>
 *   <li>只认 GitHub 与 Gitee 两家主机，别家当场拒绝——不然用户的 GitLab 令牌会被发到一个
 *       我们没打算支持的地方；</li>
 *   <li>分支留空 = 取仓库的默认分支（界面上写的就是「留空为默认」），
 *       <b>不能钉死成 master</b>：2020 年之后建的 GitHub 仓库默认分支是 main；</li>
 *   <li>401/403 与 404 必须分得开：前者是令牌问题（要用户重填），后者是找不到仓库或分支；</li>
 *   <li>任何异常 message 里都不能出现令牌——Gitee 的令牌在查询串里，把 URL 拼进报错
 *       等于把它写进日志。</li>
 * </ol>
 */
class GitProviderClientTest {

    private HttpServer server;
    private String githubApi;
    private String giteeApi;

    /** path → 预置响应 */
    private final Map<String, Resp> routes = new ConcurrentHashMap<>();
    private final List<Req> requests = new ArrayList<>();

    private record Resp(int status, byte[] body) {
        static Resp json(String body) {
            return new Resp(200, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Req(String path, String query, String authorization, String accept) {
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new Req(path, exchange.getRequestURI().getRawQuery(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Accept")));
            }
            Resp r = routes.getOrDefault(path, new Resp(404, "{\"message\":\"Not Found\"}"
                    .getBytes(StandardCharsets.UTF_8)));
            if (r.body().length == 0) {
                exchange.sendResponseHeaders(r.status(), -1);
            } else {
                exchange.sendResponseHeaders(r.status(), r.body().length);
                exchange.getResponseBody().write(r.body());
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        githubApi = base + "/gh";
        giteeApi = base + "/gt";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
        // 静态指针会跨测试类留在这个 JVM fork 里，用完必须还回默认态（中文）
        LangText.reset();
    }

    private GitProviderClient client() {
        return new GitProviderClient(githubApi, giteeApi);
    }

    private Req lastRequest() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    private int requestCount() {
        synchronized (requests) {
            return requests.size();
        }
    }

    private static final String TREE_JSON = """
            {"sha":"abc","tree":[
              {"path":"docs","type":"tree"},
              {"path":"docs/a.docx","type":"blob"},
              {"path":"b.txt","type":"blob"}
            ],"truncated":false}""";

    // ==================== parseUrl ====================

    @Test
    void parsesSupportedUrls() {
        assertThat(GitProviderClient.parseUrl("https://github.com/acme/docs.git", "main"))
                .isEqualTo(new RepoRef("github", "acme", "docs", "main"));
        assertThat(GitProviderClient.parseUrl("https://gitee.com/acme/docs/", "dev"))
                .isEqualTo(new RepoRef("gitee", "acme", "docs", "dev"));
        assertThat(GitProviderClient.parseUrl("git@github.com:acme/docs.git", "main").provider())
                .isEqualTo("github");
        assertThat(GitProviderClient.parseUrl("https://WWW.GitHub.com/acme/docs", "main").provider())
                .isEqualTo("github");
    }

    /** 分支留空 = 交给 verify 去问仓库的默认分支；这里绝不能自作主张填 master。 */
    @Test
    void blankBranchStaysUnresolved() {
        assertThat(GitProviderClient.parseUrl("git@gitee.com:acme/docs.git", null).branch()).isNull();
        assertThat(GitProviderClient.parseUrl("https://github.com/acme/docs", "  ").branch()).isNull();
    }

    @Test
    void rejectsOtherHostsAndMalformedUrls() {
        assertThatThrownBy(() -> GitProviderClient.parseUrl("https://gitlab.com/a/b", "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GitHub 与 Gitee");
        assertThatThrownBy(() -> GitProviderClient.parseUrl("https://github.com/acme", "main"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitProviderClient.parseUrl("https://github.com/a/b/tree/main", "main"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitProviderClient.parseUrl("", "main"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== verify ====================

    @Test
    void verifyResolvesDefaultBranchWhenBlank() throws Exception {
        routes.put("/gh/repos/acme/docs", Resp.json("{\"default_branch\":\"main\"}"));
        RepoRef out = client().verify(new RepoRef("github", "acme", "docs", null), "t0ken");
        assertThat(out.branch()).isEqualTo("main");
        assertThat(lastRequest().path()).isEqualTo("/gh/repos/acme/docs");
        assertThat(lastRequest().authorization()).isEqualTo("Bearer t0ken");
    }

    /** 分支是用户填的：verify 必须真去问那个分支存不存在，否则错分支要等到读文件那一刻才暴露。 */
    @Test
    void verifyChecksGivenBranchExists() throws Exception {
        routes.put("/gh/repos/acme/docs/git/trees/dev", Resp.json(TREE_JSON));
        RepoRef out = client().verify(new RepoRef("github", "acme", "docs", "dev"), "t0ken");
        assertThat(out.branch()).isEqualTo("dev");
        assertThat(lastRequest().path()).isEqualTo("/gh/repos/acme/docs/git/trees/dev");

        assertThatThrownBy(() -> client().verify(new RepoRef("github", "acme", "docs", "nope"), "t0ken"))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void giteeVerifyUsesAccessTokenParam() throws Exception {
        routes.put("/gt/repos/acme/docs", Resp.json("{\"default_branch\":\"master\"}"));
        RepoRef out = client().verify(new RepoRef("gitee", "acme", "docs", null), "gt0ken");
        assertThat(out.branch()).isEqualTo("master");
        assertThat(lastRequest().query()).contains("access_token=gt0ken");
        assertThat(lastRequest().authorization()).isNull();
    }

    // ==================== listPaths ====================

    @Test
    void listPathsKeepsOnlyBlobs() throws Exception {
        routes.put("/gh/repos/acme/docs/git/trees/main", Resp.json(TREE_JSON));
        assertThat(client().listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .containsExactly("docs/a.docx", "b.txt");
        assertThat(lastRequest().query()).contains("recursive=1");
    }

    @Test
    void giteeListPathsUsesSameTreeShape() throws Exception {
        routes.put("/gt/repos/acme/docs/git/trees/master", Resp.json(TREE_JSON));
        assertThat(client().listPaths(new RepoRef("gitee", "acme", "docs", "master"), "gt0ken"))
                .containsExactly("docs/a.docx", "b.txt");
        assertThat(lastRequest().query()).contains("access_token=gt0ken");
    }

    // ==================== readFile ====================

    @Test
    void githubReadsRawBytesWithEncodedPath() throws Exception {
        routes.put("/gh/repos/acme/docs/contents/合同/主 合同.docx",
                new Resp(200, "正文".getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "合同/主 合同.docx");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("正文");
        assertThat(lastRequest().accept()).isEqualTo("application/vnd.github.raw");
        assertThat(lastRequest().query()).contains("ref=main");
    }

    @Test
    void giteeReadsViaRawEndpoint() throws Exception {
        routes.put("/gt/repos/acme/docs/raw/b.txt", new Resp(200, "hi".getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = client().readFile(new RepoRef("gitee", "acme", "docs", "master"), "gt0ken", "b.txt");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hi");
        assertThat(lastRequest().query()).contains("ref=master").contains("access_token=gt0ken");
    }

    /** 公开仓库不必给令牌：没填就不带鉴权，而不是带一个空的。 */
    @Test
    void blankTokenSendsNoCredential() throws Exception {
        routes.put("/gh/repos/acme/docs/contents/b.txt", new Resp(200, "hi".getBytes(StandardCharsets.UTF_8)));
        client().readFile(new RepoRef("github", "acme", "docs", "main"), "  ", "b.txt");
        assertThat(lastRequest().authorization()).isNull();

        routes.put("/gt/repos/acme/docs/raw/b.txt", new Resp(200, "hi".getBytes(StandardCharsets.UTF_8)));
        client().readFile(new RepoRef("gitee", "acme", "docs", "master"), null, "b.txt");
        assertThat(lastRequest().query()).doesNotContain("access_token");
    }

    // ==================== 路径守卫 ====================

    /**
     * 路径里的 {@code ../} 必须在出站之前就被挡下。
     *
     * <p>URLEncoder 不碰点号：{@code ../../../../user/repos} 会原样留在路径里，而 GitHub / Gitee
     * 的路由会像任何 HTTP 路由一样把它归一化——于是一次带着用户令牌的 GET 就离开了这个仓库的路由，
     * 响应还顺着 ref_read 回到对话里。path 这一半来自模型给的 ref，是它读到的任何文本都能影响的。
     *
     * <p>判据是「桩服务一个请求都没收到」：只断言抛了异常的话，改成「先发出去再说」也照样绿。
     */
    @Test
    void traversalInPathNeverLeavesTheRepoRoute() {
        routes.put("/gh/user/repos", Resp.json("[]"));
        routes.put("/gt/user", Resp.json("{}"));

        assertThatThrownBy(() -> client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "../../../../user/repos"))
                .isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> client().readFile(new RepoRef("gitee", "acme", "docs", "master"), "gt0ken",
                "../../../../user"))
                .isInstanceOf(FileNotFoundException.class);
        // 单个点、绝对路径、空目录名同样出不去：`a//b` 归一化之后也少了一层
        assertThatThrownBy(() -> client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "docs/./a.docx")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "/etc/passwd")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "docs//a.docx")).isInstanceOf(FileNotFoundException.class);

        assertThat(requestCount()).isZero();
    }

    /** 分支名是用户在面板里填的，同样是不可信输入：列文件那条路也不许被它带出仓库。 */
    @Test
    void traversalInBranchNeverLeavesTheRepoRoute() {
        routes.put("/gh/user/repos", Resp.json("[]"));
        assertThatThrownBy(() -> client().listPaths(
                new RepoRef("github", "acme", "docs", "../../../../user/repos"), "t0ken"))
                .isInstanceOf(FileNotFoundException.class);
        assertThat(requestCount()).isZero();
    }

    /** 挡住的只是 . / .. / 空段：正常的多级路径、带点的文件名照旧能读。 */
    @Test
    void ordinaryDottedNamesStillWork() throws Exception {
        routes.put("/gh/repos/acme/docs/contents/a..b/.hidden/v1.2.docx",
                new Resp(200, "ok".getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "a..b/.hidden/v1.2.docx");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("ok");
    }

    @Test
    void oversizeFileIsRejectedBeforeItIsUsed() {
        routes.put("/gh/repos/acme/docs/contents/big.docx",
                new Resp(200, "0123456789ABCDEF!".getBytes(StandardCharsets.UTF_8)));
        GitProviderClient small = new GitProviderClient(githubApi, giteeApi, 16);
        assertThatThrownBy(() -> small.readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "big.docx"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
    }

    // ==================== 状态码分流 ====================

    @Test
    void unauthorizedBecomesGitAuthException() {
        routes.put("/gh/repos/acme/docs/git/trees/main", new Resp(401, "{\"message\":\"Bad credentials\"}"
                .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> client().listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .isInstanceOf(GitProviderClient.GitAuthException.class);

        routes.put("/gh/repos/acme/docs/git/trees/main", new Resp(403, new byte[0]));
        assertThatThrownBy(() -> client().listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .isInstanceOf(GitProviderClient.GitAuthException.class);
    }

    @Test
    void missingFileBecomesFileNotFound() {
        assertThatThrownBy(() -> client().readFile(new RepoRef("github", "acme", "docs", "main"), "t0ken",
                "missing.docx"))
                .isInstanceOf(FileNotFoundException.class);
    }

    /** Gitee 的令牌在查询串里：任何报错都不能把 URL（或上游响应体）拼进 message。 */
    @Test
    void tokenNeverAppearsInExceptionMessages() {
        routes.put("/gt/repos/acme/docs/git/trees/master",
                new Resp(500, "{\"message\":\"boom gt0ken\"}".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> client().listPaths(new RepoRef("gitee", "acme", "docs", "master"), "gt0ken"))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("gt0ken");

        GitProviderClient dead = new GitProviderClient("http://127.0.0.1:1", "http://127.0.0.1:1");
        assertThatThrownBy(() -> dead.listPaths(new RepoRef("gitee", "acme", "docs", "master"), "gt0ken"))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("gt0ken");
    }

    // ==================== 文案语言 ====================

    /**
     * 这个类产的 message 会被 AddinGitLinkController 的两条兜底分支原样显示在面板上
     * （地址不合规、连不上仓库）。英文界面下必须是英文——写成常量的话英文用户拿到中文，
     * 而且不报错、没人发现。
     */
    @Test
    void userFacingMessagesFollowTheInterfaceLanguage() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);

        assertThatThrownBy(() -> GitProviderClient.parseUrl("https://gitlab.com/a/b", "main"))
                .hasMessageContaining("GitHub and Gitee");
        assertThatThrownBy(() -> GitProviderClient.parseUrl("", "main"))
                .hasMessageContaining("repository URL");
        assertThatThrownBy(() -> GitProviderClient.parseUrl("not a url", "main"))
                .hasMessageContaining("repository URL");

        GitProviderClient dead = new GitProviderClient("http://127.0.0.1:1", "http://127.0.0.1:1");
        assertThatThrownBy(() -> dead.listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .hasMessageContaining("Could not reach GitHub");

        routes.put("/gh/repos/acme/docs/git/trees/main", new Resp(500, new byte[0]));
        assertThatThrownBy(() -> client().listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .hasMessageContaining("GitHub returned 500");
    }

    @Test
    void malformedTreeResponseIsAnIoErrorNotACrash() {
        routes.put("/gh/repos/acme/docs/git/trees/main", Resp.json("<html>gateway</html>"));
        assertThatThrownBy(() -> client().listPaths(new RepoRef("github", "acme", "docs", "main"), "t0ken"))
                .isInstanceOf(IOException.class);
    }
}
