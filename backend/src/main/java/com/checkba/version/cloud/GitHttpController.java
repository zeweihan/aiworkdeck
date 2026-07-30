package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Git smart HTTP 协议端点（团队服务器侧）。
 * 不用 org.eclipse.jgit.http.server 的 GitServlet：那是 javax.servlet 系,
 * 本项目是 Boot 3 / jakarta。UploadPack/ReceivePack 本身 servlet 无关，直接对接流。
 */
@RestController
@RequestMapping("/git")
public class GitHttpController {

    static final String UPLOAD_PACK = "git-upload-pack";
    static final String RECEIVE_PACK = "git-receive-pack";

    private final ProjectRepoService repoService;

    public GitHttpController(ProjectRepoService repoService) {
        this.repoService = repoService;
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
    }

    @PostMapping("/{projectId}.git/git-upload-pack")
    public void uploadPack(@PathVariable long projectId,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
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
    }

    @PostMapping("/{projectId}.git/git-receive-pack")
    public void receivePack(@PathVariable long projectId,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
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
            rp.receive(body(request), response.getOutputStream(), null);
        }
    }

    /** Task 6 在这里挂 PostReceiveHook（push 落库）。本任务空实现。 */
    private void configureReceivePack(ReceivePack rp, long projectId) {
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
}
