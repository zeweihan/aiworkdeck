package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import com.checkba.version.WorkSessionService;
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

    public GitHttpController(ProjectRepoService repoService, GitAccessService access,
                             WorkSessionService sessionService) {
        this.repoService = repoService;
        this.access = access;
        this.sessionService = sessionService;
    }

    @GetMapping("/{projectId}.git/info/refs")
    public void infoRefs(@PathVariable long projectId,
                         @RequestParam(value = "service", required = false) String service,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        if (!UPLOAD_PACK.equals(service) && !RECEIVE_PACK.equals(service)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "smart protocol only");
            return;
        }
        try {
            if (!deny(response, () -> access.authorize(request, projectId, RECEIVE_PACK.equals(service)))) return;
            if (!repoService.isInitialized(projectId)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-" + service + "-advertisement");
            noCache(response);
            try (Repository repo = repoService.open(projectId)) {
                PacketLineOut out = new PacketLineOut(response.getOutputStream());
                out.writeString("# service=" + service + "\n");
                out.end();
                if (UPLOAD_PACK.equals(service)) {
                    UploadPack up = new UploadPack(repo);
                    up.setBiDirectionalPipe(false);
                    up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
                } else {
                    ReceivePack rp = new ReceivePack(repo);
                    configureReceivePack(rp, projectId);
                    rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
                }
            }
        } catch (Exception e) {
            // 全局 @ExceptionHandler(Exception.class) 会把异常统一改写成 HTTP 200 + JSON，
            // 把这段本该是 git 协议响应的输出污染成客户端读不懂的 "invalid advertisement"，
            // 所以这里要在协议层自己兜底。
            log.warn("git info/refs 处理失败: projectId={}, service={}", projectId, service, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/{projectId}.git/git-upload-pack")
    public void uploadPack(@PathVariable long projectId,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        try {
            if (!deny(response, () -> access.authorize(request, projectId, false))) return;
            if (!repoService.isInitialized(projectId)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-git-upload-pack-result");
            noCache(response);
            try (Repository repo = repoService.open(projectId)) {
                UploadPack up = new UploadPack(repo);
                up.setBiDirectionalPipe(false);
                up.upload(body(request), response.getOutputStream(), null);
            }
        } catch (ZipException e) {
            // 请求体不是合法的 gzip 流，是客户端的问题，不是服务端错误
            log.warn("git-upload-pack 请求体 gzip 解压失败: projectId={}", projectId, e);
            failSafely(response, HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            log.warn("git-upload-pack 处理失败: projectId={}", projectId, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/{projectId}.git/git-receive-pack")
    public void receivePack(@PathVariable long projectId,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        try {
            if (!deny(response, () -> access.authorize(request, projectId, true))) return;
            if (!repoService.isInitialized(projectId)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType("application/x-git-receive-pack-result");
            noCache(response);
            try (Repository repo = repoService.open(projectId)) {
                ReceivePack rp = new ReceivePack(repo);
                rp.setBiDirectionalPipe(false);
                configureReceivePack(rp, projectId);
                sessionService.runLocked(projectId, () -> {
                    try {
                        rp.receive(body(request), response.getOutputStream(), null);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (ZipException e) {
            // 请求体不是合法的 gzip 流，是客户端的问题，不是服务端错误
            log.warn("git-receive-pack 请求体 gzip 解压失败: projectId={}", projectId, e);
            failSafely(response, HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            log.warn("git-receive-pack 处理失败: projectId={}", projectId, e);
            failSafely(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
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
    private void configureReceivePack(ReceivePack rp, long projectId) {
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
