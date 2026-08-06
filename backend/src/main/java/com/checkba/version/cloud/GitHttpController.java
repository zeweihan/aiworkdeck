package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import com.checkba.version.WorkSessionService;
import com.checkba.version.memory.MemoryRealm;
import com.checkba.version.memory.MemoryRepoService;
import com.checkba.version.memory.MemorySyncService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Git smart HTTP 协议端点（团队服务器侧）。
 * 不用 org.eclipse.jgit.http.server 的 GitServlet：那是 javax.servlet 系,
 * 本项目是 Boot 3 / jakarta。UploadPack/ReceivePack 本身 servlet 无关，直接对接流。
 *
 * 仓库键路由（Phase A 泛化）：
 *   /git/{projectId}.git             —— 项目文档仓库（v2 既有行为，一字未动）
 *   /git/user-{id}-memory.git        —— 用户记忆仓库（owner-only）
 *   /git/project-{id}-memory.git     —— 项目记忆仓库（复用项目成员权限）
 * 记忆仓库没有 prepare-remote 流程：鉴权通过后首次访问自动建空仓等首推。
 * 记忆仓库的 receive 与 MemorySyncService 的同步循环共用同一把 per-repoKey 锁；
 * 项目文档仓库照旧走 WorkSessionService.runLocked（per-projectId）。
 */
@RestController
@RequestMapping("/git")
public class GitHttpController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GitHttpController.class);

    static final String UPLOAD_PACK = "git-upload-pack";
    static final String RECEIVE_PACK = "git-receive-pack";

    private final ProjectRepoService repoService;
    private final GitAccessService access;
    private final WorkSessionService sessionService;
    private final MemoryRepoService memoryRepoService;
    private final MemorySyncService memorySyncService;

    public GitHttpController(ProjectRepoService repoService, GitAccessService access,
                             WorkSessionService sessionService,
                             MemoryRepoService memoryRepoService,
                             MemorySyncService memorySyncService) {
        this.repoService = repoService;
        this.access = access;
        this.sessionService = sessionService;
        this.memoryRepoService = memoryRepoService;
        this.memorySyncService = memorySyncService;
    }

    /**
     * 仓库名解析：纯数字 = 项目文档仓库；user-{id}-memory / project-{id}-memory =
     * 记忆仓库；其余一律 null（404）。projectId 与 memoryRealm 恰有其一非空。
     */
    record RepoTarget(Long projectId, MemoryRealm memoryRealm) {}

    static RepoTarget parseRepoName(String name) {
        if (name != null && name.matches("\\d{1,18}")) {
            return new RepoTarget(Long.parseLong(name), null);
        }
        MemoryRealm realm = MemoryRealm.parse(name);
        return realm == null ? null : new RepoTarget(null, realm);
    }

    @GetMapping("/{repo}.git/info/refs")
    public void infoRefs(@PathVariable String repo,
                         @RequestParam(value = "service", required = false) String service,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        if (!UPLOAD_PACK.equals(service) && !RECEIVE_PACK.equals(service)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "smart protocol only");
            return;
        }
        RepoTarget target = parseRepoName(repo);
        if (target == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            if (!deny(response, () -> authorizeTarget(request, target, RECEIVE_PACK.equals(service)))) return;
            if (!ensureRepoAvailable(target)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-" + service + "-advertisement");
            noCache(response);
            try (Repository repository = openTarget(target)) {
                PacketLineOut out = new PacketLineOut(response.getOutputStream());
                out.writeString("# service=" + service + "\n");
                out.end();
                if (UPLOAD_PACK.equals(service)) {
                    UploadPack up = new UploadPack(repository);
                    up.setBiDirectionalPipe(false);
                    up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
                } else {
                    ReceivePack rp = new ReceivePack(repository);
                    configureReceivePack(rp, target);
                    rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
                }
            }
        } catch (Exception e) {
            // 全局 @ExceptionHandler(Exception.class) 会把异常统一改写成 HTTP 200 + JSON，
            // 把这段本该是 git 协议响应的输出污染成客户端读不懂的 "invalid advertisement"，
            // 所以这里要在协议层自己兜底。
            log.warn("git info/refs 处理失败: repo={}, service={}", repo, service, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/{repo}.git/git-upload-pack")
    public void uploadPack(@PathVariable String repo,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        RepoTarget target = parseRepoName(repo);
        if (target == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            if (!deny(response, () -> authorizeTarget(request, target, false))) return;
            if (!ensureRepoAvailable(target)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-git-upload-pack-result");
            noCache(response);
            try (Repository repository = openTarget(target)) {
                UploadPack up = new UploadPack(repository);
                up.setBiDirectionalPipe(false);
                up.upload(body(request), response.getOutputStream(), null);
            }
        } catch (ZipException e) {
            // 请求体不是合法的 gzip 流，是客户端的问题，不是服务端错误
            log.warn("git-upload-pack 请求体 gzip 解压失败: repo={}", repo, e);
            failSafely(response, HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            log.warn("git-upload-pack 处理失败: repo={}", repo, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/{repo}.git/git-receive-pack")
    public void receivePack(@PathVariable String repo,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        RepoTarget target = parseRepoName(repo);
        if (target == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            if (!deny(response, () -> authorizeTarget(request, target, true))) return;
            if (!ensureRepoAvailable(target)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-git-receive-pack-result");
            noCache(response);
            try (Repository repository = openTarget(target)) {
                ReceivePack rp = new ReceivePack(repository);
                rp.setBiDirectionalPipe(false);
                configureReceivePack(rp, target);
                runReceiveLocked(target, () -> {
                    try {
                        rp.receive(body(request), response.getOutputStream(), null);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (ZipException e) {
            // 请求体不是合法的 gzip 流，是客户端的问题，不是服务端错误
            log.warn("git-receive-pack 请求体 gzip 解压失败: repo={}", repo, e);
            failSafely(response, HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            log.warn("git-receive-pack 处理失败: repo={}", repo, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private Long authorizeTarget(HttpServletRequest request, RepoTarget target, boolean write) {
        if (target.projectId() != null) {
            return access.authorize(request, target.projectId(), write);
        }
        MemoryRealm realm = target.memoryRealm();
        return realm.kind() == MemoryRealm.Kind.USER
                ? access.authorizeUserMemory(request, realm.ownerId(), write)
                : access.authorize(request, realm.ownerId(), write);
    }

    /**
     * 项目文档仓库必须已由 prepare-remote 建好（否则 404，v2 既有语义）；
     * 记忆仓库鉴权通过即自动建空仓（spec Phase A 第 7 条：跳过 prepare-remote 流程）。
     */
    private boolean ensureRepoAvailable(RepoTarget target) {
        if (target.projectId() != null) {
            return repoService.isInitialized(target.projectId());
        }
        String repoKey = target.memoryRealm().repoKey();
        if (!memoryRepoService.isInitialized(repoKey)) {
            memoryRepoService.init(repoKey);
        }
        return true;
    }

    private Repository openTarget(RepoTarget target) {
        return target.projectId() != null
                ? repoService.open(target.projectId())
                : memoryRepoService.open(target.memoryRealm().repoKey());
    }

    /** receive 与本地提交路径互斥：项目仓库 per-projectId 锁，记忆仓库 per-repoKey 锁。 */
    private void runReceiveLocked(RepoTarget target, Runnable body) {
        if (target.projectId() != null) {
            sessionService.runLocked(target.projectId(), body);
        } else {
            memorySyncService.runLocked(target.memoryRealm().repoKey(), body);
        }
    }

    private void configureReceivePack(ReceivePack rp, RepoTarget target) {
        if (target.projectId() != null) {
            configureProjectReceivePack(rp, target.projectId());
        } else {
            configureMemoryReceivePack(rp, target.memoryRealm().repoKey());
        }
    }

    /**
     * pre-receive 分两步：① 服务器仓库停在合并窗口（MERGING，网页端律师正在做冲突三选一）
     * 时拒收整个 push——窗口期间放进来的 push 会让 master 前进，随后的裁决提交以新 master
     * 为第一父落地，同事刚推上来的内容被静默回退；② 正常态先停靠主线脏区（这次 push 的
     * old-sha 因此对不上、被 git 原生拒绝）。post-receive 在 master 真正前进后做路径级落库
     * （见 WorkSessionService 的 dockDirtyMainlineForReceive/ingestPushedMainline）。
     * setObjectChecker：push 上来的对象不可信（任何有写权限的成员都能手工构造 pack），
     * 开 JGit 的对象格式校验，畸形对象在入库前就被拒。
     */
    private void configureProjectReceivePack(ReceivePack rp, long projectId) {
        rp.setObjectChecker(new ObjectChecker());
        rp.setPreReceiveHook((pack, commands) -> {
            if (repositoryMergingOrUnknown(projectId)) {
                for (ReceiveCommand cmd : commands) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
                            "project is resolving a merge, retry later");
                }
                return;
            }
            sessionService.dockDirtyMainlineForReceive(projectId);
        });
        rp.setPostReceiveHook((pack, commands) -> {
            for (ReceiveCommand cmd : commands) {
                if ("refs/heads/master".equals(cmd.getRefName())
                        && cmd.getResult() == ReceiveCommand.Result.OK) {
                    sessionService.ingestPushedMainline(projectId,
                            cmd.getOldId().name(), cmd.getNewId().name());
                }
            }
        });
    }

    /**
     * 记忆仓库的 receive：不需要 pre-receive 守卫——记忆仓库从不停留在 MERGING（LWW 全
     * 自动），工作树也不承载未提交的用户编辑（可弃的物化区，每轮同步先重置）。post-receive
     * 把这次 push 变化的文件回灌进服务端 DB（尽力而为，失败不影响 push 本身）。
     */
    private void configureMemoryReceivePack(ReceivePack rp, String repoKey) {
        rp.setObjectChecker(new ObjectChecker());
        rp.setPostReceiveHook((pack, commands) -> {
            for (ReceiveCommand cmd : commands) {
                if ("refs/heads/master".equals(cmd.getRefName())
                        && cmd.getResult() == ReceiveCommand.Result.OK) {
                    memorySyncService.ingestPushedMemory(repoKey,
                            cmd.getOldId().name(), cmd.getNewId().name());
                }
            }
        });
    }

    /**
     * 合并窗口判定，查询失败**按合并中处理（拒收）**——与「版本记录不阻断主流程」的
     * 常规纪律相反，是有意的：这里放行的代价不是少记一笔版本，而是可能把同事的 push
     * 收进一个正在裁决的仓库、裁决提交把它静默回退；拒收的代价只是客户端稍后重试。
     * 宁可让客户端重试，不冒静默回退内容的风险。
     */
    private boolean repositoryMergingOrUnknown(long projectId) {
        try {
            return repoService.repositoryMerging(projectId);
        } catch (Exception e) {
            log.warn("pre-receive 合并态查询失败，按合并中处理（拒收这次 push）: projectId={}",
                    projectId, e);
            return true;
        }
    }

    /**
     * 鉴权失败时写响应并返回 false，让端点方法直接 return——不落入下面通用的
     * catch(Exception) / failSafely 500 兜底。401 带 WWW-Authenticate，JGit 客户端靠它重试凭据；
     * 403 直接 sendError。
     */
    private boolean deny(HttpServletResponse response, java.util.function.Supplier<Long> auth)
            throws IOException {
        try {
            auth.get();
            return true;
        } catch (GitAccessDeniedException e) {
            if (e.statusCode() == 401) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"AIWorkdeck Git\"");
            }
            response.sendError(e.statusCode());
            return false;
        }
    }

    private InputStream body(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        if ("gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"))) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
    }

    /**
     * git 协议出错时的兜底：还没往客户端写字节就能改状态码；
     * 字节已经流出去（response 已 committed）就只能靠上面的 log.warn 留痕，
     * 状态码换不了了——这是 HTTP 协议本身的限制，不是这里能绕开的。
     */
    private void failSafely(HttpServletResponse response, int status) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
            response.sendError(status);
        }
    }
}
