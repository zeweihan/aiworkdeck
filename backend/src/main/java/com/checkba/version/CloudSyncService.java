package com.checkba.version;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 云端同步业务语义（上传/更新/共享/接入）。只有这里认识 CloudConnection/ProjectRemote；
 * Git 细节在 ProjectRepoService，工作段语义在 WorkSessionService。
 * 与 WorkSessionService 同包：复用同一把 repoLock（包内可见），云端操作与本地
 * 提交路径互斥是硬要求。
 * 网络失败纪律：云端不可达只置状态（pendingUpload/黄灯），绝不阻断本地流程。
 */
@Service
public class CloudSyncService {

    private static final Logger log = LoggerFactory.getLogger(CloudSyncService.class);

    private final ProjectRepoService repoService;
    private final WorkSessionService sessionService;
    private final ProjectTreeManifestService manifestService;
    private final ProjectFileRepository fileRepository;
    private final CloudConnectionRepository connectionRepository;
    private final ProjectRemoteRepository remoteRepository;

    public CloudSyncService(ProjectRepoService repoService,
                             WorkSessionService sessionService,
                             ProjectTreeManifestService manifestService,
                             ProjectFileRepository fileRepository,
                             CloudConnectionRepository connectionRepository,
                             ProjectRemoteRepository remoteRepository) {
        this.repoService = repoService;
        this.sessionService = sessionService;
        this.manifestService = manifestService;
        this.fileRepository = fileRepository;
        this.connectionRepository = connectionRepository;
        this.remoteRepository = remoteRepository;
    }

    public enum UploadStatus { UPLOADED, REMOTE_AHEAD, OFFLINE_PENDING, NOT_LINKED }

    public record UploadResult(UploadStatus status, String message) {}

    /** 用账号密码换一个长期设备令牌，本地存下来（服务端 `/api/auth/device-token`）。 */
    public CloudConnection connect(String serverUrl, String username,
                                    String password, String deviceName) {
        String base = serverUrl.replaceAll("/+$", "");
        String body = JSONUtil.toJsonStr(Map.of(
                "username", username, "password", password, "name", deviceName));
        JSONObject resp = JSONUtil.parseObj(httpPost(base + "/api/auth/device-token", body));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing("连接云端失败：" + resp.getStr("message", "账号或密码不对"));
        }
        JSONObject data = resp.getJSONObject("data");
        CloudConnection conn = new CloudConnection();
        conn.setServerUrl(base);
        conn.setUsername(data.getStr("username"));
        conn.setDisplayName(data.getStr("displayName"));
        conn.setDeviceToken(data.getStr("token"));
        conn.setCreatedAt(LocalDateTime.now());
        return connectionRepository.save(conn);
    }

    /** 断开一个云端连接：尽力撤远端令牌 + 删本地连接与所有关联的项目绑定。 */
    public void disconnect(long connectionId) {
        connectionRepository.findById(connectionId).ifPresent(conn -> {
            try {
                httpPost(conn.getServerUrl() + "/api/auth/device-token/0/revoke", "{}");
            } catch (Exception ignored) {
                // 尽力撤销，失败不阻断本地断开
            }
            remoteRepository.findByConnectionId(connectionId).forEach(remoteRepository::delete);
            connectionRepository.delete(conn);
        });
    }

    public java.util.List<CloudConnection> listConnections() {
        return connectionRepository.findAll();
    }

    /**
     * 推主线（含里程碑标签）到云端。被拒（PushOutcome.rejected）统一按 REMOTE_AHEAD 归类——
     * rejected 严格说不只是「有人推进了主线」这一种情况（NOT_ATTEMPTED/AWAITING_REPORT
     * 等 JGit 内部状态也会落进这个字段），这里做的是保守归类：只要没有 pushed 成功，
     * 一律先置黄灯（pendingUpload）等下次机会（Task 9 升级为自动合并再重推）。
     */
    public UploadResult uploadToCloud(long projectId, boolean background) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            var remoteOpt = remoteRepository.findByProjectId(projectId);
            if (remoteOpt.isEmpty()) {
                return new UploadResult(UploadStatus.NOT_LINKED, null);
            }
            ProjectRemote remote = remoteOpt.get();
            CloudConnection conn = connectionRepository.findById(remote.getConnectionId())
                    .orElseThrow(() -> new VersionException("云端连接不存在: " + remote.getConnectionId()));
            if (repoService.repositoryMerging(projectId)) {
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "请先处理正在进行的合并");
            }
            try {
                ProjectRepoService.PushOutcome out = repoService.pushMainlineToOrigin(
                        projectId, conn.getUsername(), conn.getDeviceToken());
                if (out.pushed()) {
                    remote.setPendingUpload(false);
                    remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                    remoteRepository.save(remote);
                    return new UploadResult(UploadStatus.UPLOADED, null);
                }
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "云端有同事的新版本");
            } catch (VersionException e) {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                if (background) {
                    log.warn("后台上传失败，转入待上传: project={}", projectId, e);
                    return new UploadResult(UploadStatus.OFFLINE_PENDING, null);
                }
                throw VersionException.userFacing("云端暂时连不上，改动已记为待上传");
            }
        } finally {
            lock.unlock();
        }
    }

    /** 不联网的云端状态快照（/status 与云端状态区吃它）。 */
    public Map<String, Object> cloudStatus(long projectId) {
        var remoteOpt = remoteRepository.findByProjectId(projectId);
        if (remoteOpt.isEmpty()) {
            return Map.of("linked", false);
        }
        ProjectRemote remote = remoteOpt.get();
        String serverUrl = connectionRepository.findById(remote.getConnectionId())
                .map(CloudConnection::getServerUrl).orElse(null);
        String origin = repoService.originMasterSha(projectId);
        boolean remoteAhead = origin != null
                && !repoService.isAncestor(projectId, origin, repoService.mainBranch());
        Map<String, Object> m = new HashMap<>();
        m.put("linked", true);
        m.put("serverUrl", serverUrl);
        m.put("remoteProjectId", remote.getRemoteProjectId());
        m.put("pendingUpload", Boolean.TRUE.equals(remote.getPendingUpload()));
        m.put("remoteAhead", remoteAhead);
        return m;
    }

    /** 结束工作 → 后台自动上传（spec 决策 3）。绝不能让上传异常反向影响已经结束的工作段。 */
    @EventListener
    @Async("taskExecutor")
    public void onMainlineMerged(WorkSessionService.MainlineMergedEvent event) {
        try {
            uploadToCloud(event.projectId(), true);
        } catch (Exception e) {
            log.warn("自动上传异常（已吞）: project={}", event.projectId(), e);
        }
    }

    /** 单测覆写此 seam 打桩（PluginMarketService.httpGet 同款约定）。 */
    protected String httpPost(String url, String jsonBody) {
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .body(jsonBody)
                .setConnectionTimeout(5000)
                .setReadTimeout(15000)
                .execute()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("云端请求失败 (HTTP " + resp.getStatus() + ")");
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("云端不可达: " + e.getMessage(), e);
        }
    }
}
