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
import java.util.List;
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

    /** CONFLICT（Task 9）：被拒后自动合并遇到冲突，仓库停在 MERGING 等裁决（同云端更新的冲突窗口）。 */
    public enum UploadStatus { UPLOADED, REMOTE_AHEAD, OFFLINE_PENDING, NOT_LINKED, CONFLICT }

    public record UploadResult(UploadStatus status, String message) {}

    public enum UpdateStatus { UP_TO_DATE, UPDATED, CONFLICT, OFFLINE, NOT_LINKED }

    public record UpdateResult(UpdateStatus status, List<Long> affectedFileIds,
                               Map<String, Object> conflict) {}

    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static final String CLOUD_MERGE_TITLE = "云端更新";
    private static final String CLOUD_SIDE_LABEL = "云端";

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
        conn.setTokenId(data.getLong("tokenId", null));
        conn.setCreatedAt(LocalDateTime.now());
        return connectionRepository.save(conn);
    }

    /** 断开一个云端连接：尽力撤远端令牌 + 删本地连接与所有关联的项目绑定。 */
    public void disconnect(long connectionId) {
        connectionRepository.findById(connectionId).ifPresent(conn -> {
            if (conn.getTokenId() != null) {
                try {
                    JSONObject resp = JSONUtil.parseObj(httpPost(
                            conn.getServerUrl() + "/api/auth/device-token/" + conn.getTokenId() + "/revoke",
                            "{}", conn.getDeviceToken()));
                    if (resp.getInt("code", 1) != 0) {
                        log.warn("远端撤销设备令牌未成功: connection={}, message={}",
                                connectionId, resp.getStr("message"));
                    }
                } catch (Exception e) {
                    log.warn("远端撤销设备令牌失败，仅做本地断开: connection={}", connectionId, e);
                }
            }
            remoteRepository.findByConnectionId(connectionId).forEach(remoteRepository::delete);
            connectionRepository.delete(conn);
        });
    }

    public java.util.List<CloudConnection> listConnections() {
        return connectionRepository.findAll();
    }

    /**
     * 推主线（含里程碑标签）到云端。被拒（PushOutcome.rejected）曾经统一按 REMOTE_AHEAD
     * 归类置黄灯（Task 8），Task 9 升级：守卫允许时（无 ACTIVE 工作段、不在稿上——仓库
     * 是否在合并中已经在上面单独判过）自动走 {@link #integrateFromCloud} 同一条内核，
     * 干净合并/快进后重推成功才算 UPLOADED，遇到真实内容冲突则新增 UploadStatus.CONFLICT
     * 让仓库停在裁决窗口；守卫不允许（有未收尾的工作/站在稿上）仍然维持旧行为：
     * 只置 pendingUpload，等律师自己处理完再手动上传或从云端更新。
     *
     * background（结束工作后台自动上传）没有真实用户上下文，自动合并需要的提交作者身份
     * 用当前云端连接的账号名兜底（conn 在这条路径上总是在场——远端已绑定才走得到这里）。
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
            CloudConnection conn = connectionOf(remote);
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
                if (canAutoIntegrate(projectId)) {
                    String authorName = conn.getDisplayName() != null ? conn.getDisplayName() : conn.getUsername();
                    UpdateResult integrated = integrateFromCloud(projectId, conn, null, authorName);
                    if (integrated.status() == UpdateStatus.UPDATED
                            || integrated.status() == UpdateStatus.UP_TO_DATE) {
                        return new UploadResult(UploadStatus.UPLOADED, null);
                    }
                    if (integrated.status() == UpdateStatus.CONFLICT) {
                        remote.setPendingUpload(true);
                        remoteRepository.save(remote);
                        return new UploadResult(UploadStatus.CONFLICT, "云端有同事的新版本，两边都改了同一处，需要选一下留哪份");
                    }
                    // 走到这里只剩 OFFLINE（fetch 联不上）：整合本身没能进行，落回
                    // 旧行为。integrateFromCloud 直接复用这里已经解析好的 conn、
                    // 不会再走一次 remoteOpt 查找，NOT_LINKED 不会从这条路径出现。
                }
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "云端有同事的新版本，结束当前工作后再上传");
            } catch (VersionException e) {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                log.warn("上传/整合失败: project={}", projectId, e);
                if (background) {
                    return new UploadResult(UploadStatus.OFFLINE_PENDING, null);
                }
                throw VersionException.userFacing("上传没能完成，改动已记为待上传");
            }
        } finally {
            lock.unlock();
        }
    }

    /** 上传被拒后能否自动整合：仓库是否在合并中，调用方已经判过；这里只补活跃工作/稿两道守卫。 */
    private boolean canAutoIntegrate(long projectId) {
        return sessionService.activeSession(projectId).isEmpty() && !sessionService.onDraftBranch(projectId);
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

    // ==================== 从云端更新（Task 9） ====================
    //
    // 方向钉死：这里的合并永远是「origin/master 并入本地 master」——ours=本地=我这边的，
    // theirs=云端=云端的。Resolution.MAIN=用我这边的、DRAFT=用云端的、BOTH=两份都留
    // （副本来自云端侧，applyResolution 的 draftName 传 CLOUD_SIDE_LABEL）。与 Task 7
    // 结束工作撞车的方向相反（那边 MAIN=同事的）——前端标签按语境映射，不在本类处理。
    //
    // 语义护栏：快进路径清单用 applyToDatabase 全量同步（目标状态即真相，与 revertTo/
    // 切线同口径）；真合并路径用 unionApply + capture 同一提交（两条已分叉线的合并，
    // 与 adoptDraft 同口径，地雷 #21 的「干净路径与冲突路径必须以同一种方式提交」照抄）。

    /**
     * 从云端更新：dock 当前线 → fetch → 能快进就快进，不能快进就真合并。
     * 前置守卫都是 userFacing——律师看得懂「先收尾工作」「先回到主线」，看不懂 Git 状态。
     */
    public UpdateResult updateFromCloud(long projectId, Long userId, String userName) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            var remoteOpt = remoteRepository.findByProjectId(projectId);
            if (remoteOpt.isEmpty()) return new UpdateResult(UpdateStatus.NOT_LINKED, List.of(), null);
            requireCleanForCloudOps(projectId);
            return integrateFromCloud(projectId, connectionOf(remoteOpt.get()), userId, userName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 更新/上传共用的整合内核：dock → fetch → 快进或真合并。调用方负责前置守卫与取锁
     * （repoLock 可重入，uploadToCloud 的自动整合分支在已持锁状态下直接调用这里）。
     *
     * userId 可能为 null（uploadToCloud 的自动整合没有真实用户上下文，见其类注释）——
     * dockCurrentLine 在守卫已确保「无活跃工作/不在稿上」时不会真的用到它（见其实现的
     * 两条分支），真合并提交的作者邮箱走 {@link #authorEmail}，null 时退化为账号名。
     */
    private UpdateResult integrateFromCloud(long projectId, CloudConnection conn,
                                            Long userId, String userName) {
        sessionService.dockCurrentLine(projectId, userId, userName);
        String remoteSha;
        try {
            remoteSha = repoService.fetchFromOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
        } catch (VersionException e) {
            return new UpdateResult(UpdateStatus.OFFLINE, List.of(), null);
        }
        if (remoteSha == null
                || repoService.isAncestor(projectId, remoteSha, repoService.mainBranch())) {
            return new UpdateResult(UpdateStatus.UP_TO_DATE, List.of(), null);
        }
        String tipBefore = repoService.resolveRef(projectId, repoService.mainBranch());
        if (repoService.isAncestor(projectId, repoService.mainBranch(), ORIGIN_MASTER)) {
            // 快进：目标状态即真相 → applyToDatabase 全量同步（切线/退回同口径）
            repoService.fastForwardMainline(projectId, ORIGIN_MASTER);
            var manifest = manifestService.readAtRef(projectId, "HEAD");
            if (manifest != null) manifestService.applyToDatabase(projectId, manifest);
            return new UpdateResult(UpdateStatus.UPDATED, affectedSince(projectId, tipBefore), null);
        }
        // 真合并：两条已分叉的线
        MergeOutcome outcome = repoService.mergeNoCommit(projectId, ORIGIN_MASTER,
                CLOUD_MERGE_TITLE, userName, authorEmail(userId, userName));
        if (outcome.mergeSha() != null) {
            // ALREADY_UP_TO_DATE：上面两次 isAncestor 判断之间仓库状态变化的边界情况，
            // 没有待提交的合并（mergeNoCommit 的契约，见其 Javadoc）。
            return new UpdateResult(UpdateStatus.UP_TO_DATE, List.of(), null);
        }
        if (!outcome.success()) {
            List<String> conflicts = WorkSessionService.userVisibleConflicts(outcome.conflictingPaths());
            if (conflicts.isEmpty()) {
                // 只有内部的文件树清单冲突（两边都改了 .awd/tree.json 的文本，真实文件
                // 互不相干）。律师不认识这个文件、也无从选择，清单并集本来就要按并集
                // 规则重写它——自己裁决掉，别弹窗打扰他（同 WorkSessionService.adoptDraft
                // 的同款自愈，理由见地雷 #21）。
                return completeCloudMerge(projectId, tipBefore, remoteSha, conn, userId, userName);
            }
            return new UpdateResult(UpdateStatus.CONFLICT, List.of(), cloudConflictPayload(projectId));
        }
        return completeCloudMerge(projectId, tipBefore, remoteSha, conn, userId, userName);
    }

    /** 冲突裁决：逐文件三选一，choices 必须覆盖全部待选文件，随后与干净路径走同一条收尾。 */
    public UpdateResult resolveCloudMerge(long projectId,
                                          Map<String, WorkSessionService.Resolution> resolutions,
                                          Long userId, String userName) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("现在没有等着做选择的更新");
            }
            String cloudTip = repoService.mergeHeadRef(projectId);
            if (cloudTip == null || !cloudTip.equals(repoService.originMasterSha(projectId))) {
                throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
            }
            List<String> rawConflicts = repoService.conflictingPaths(projectId);
            if (rawConflicts.isEmpty()) {
                throw new VersionException("冲突记录已丢失，无法安全完成更新: project=" + projectId);
            }
            String mainTip = repoService.resolveRef(projectId, "HEAD");
            List<String> conflicts = WorkSessionService.userVisibleConflicts(rawConflicts);
            Map<String, WorkSessionService.Resolution> choices =
                    resolutions == null ? Map.of() : resolutions;
            for (String path : conflicts) {
                if (choices.get(path) == null) throw VersionException.userFacing("还有文件没有做出选择");
            }
            for (String path : conflicts) {
                sessionService.applyResolution(projectId, path, choices.get(path),
                        mainTip, cloudTip, CLOUD_SIDE_LABEL);
            }
            var remote = remoteRepository.findByProjectId(projectId).orElseThrow();
            return completeCloudMerge(projectId, mainTip, cloudTip,
                    connectionOf(remote), userId, userName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 无损中止一次云端更新的合并窗口：两边都不动，等律师改天再更新。
     * 守卫同 {@link #resolveCloudMerge}：不能无条件 abortMerge——误调会把进行中的
     * 别的合并窗口（例如稿采纳/结束工作冲突）静默销毁，还告诉律师「分毫未动」。
     */
    public String abortCloudMerge(long projectId) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("现在没有等着做选择的更新");
            }
            String cloudTip = repoService.mergeHeadRef(projectId);
            if (cloudTip == null || !cloudTip.equals(repoService.originMasterSha(projectId))) {
                throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
            }
            repoService.abortMerge(projectId);
            return "这次更新没有完成，你的内容分毫未动";
        } finally {
            lock.unlock();
        }
    }

    /**
     * 干净合并或裁决后的云端合并统一收尾：清单并集（并集语义同采纳，地雷 #21——干净路径
     * 与冲突裁决路径必须以同一种方式提交，清单与内容进同一个双亲提交）→ 提交 → 自动重推。
     * 重推失败（网络问题）不回滚——合并已经落地，只是回传没成，转入待上传，绝不丢改动。
     */
    private UpdateResult completeCloudMerge(long projectId, String tipBefore, String cloudTip,
                                            CloudConnection conn, Long userId, String userName) {
        var cloudManifest = manifestService.readAtRef(projectId, cloudTip);
        if (cloudManifest != null) manifestService.unionApply(projectId, cloudManifest);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        repoService.commitMergeResolution(projectId, CLOUD_MERGE_TITLE,
                userName, authorEmail(userId, userName));
        try {
            repoService.pushMainlineToOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
            remoteRepository.findByProjectId(projectId).ifPresent(remote -> {
                remote.setPendingUpload(false);
                remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                remoteRepository.save(remote);
            });
        } catch (Exception e) {
            log.warn("云端更新合并已落地，重推失败，转入待上传: project={}", projectId, e);
            remoteRepository.findByProjectId(projectId).ifPresent(remote -> {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
            });
        }
        return new UpdateResult(UpdateStatus.UPDATED, affectedSince(projectId, tipBefore), null);
    }

    /** 受影响文件 id：口径同 revertTo，动作前后的 HEAD 差异反向传给 diffNameStatus。 */
    private List<Long> affectedSince(long projectId, String tipBefore) {
        try {
            var changes = repoService.diffNameStatus(projectId, "HEAD", tipBefore);
            return sessionService.resolveAffectedFileIds(projectId, changes);
        } catch (Exception e) {
            log.warn("云端更新计算受影响文件失败: project={}", projectId, e);
            return List.of();
        }
    }

    /** updateFromCloud 的前置守卫：没有进行中的合并/工作/稿，理由同采纳前置（避免几件事缠在一起）。 */
    private void requireCleanForCloudOps(long projectId) {
        if (repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing("请先处理正在进行的合并");
        }
        if (sessionService.activeSession(projectId).isPresent()) {
            throw VersionException.userFacing("请先结束或丢弃当前工作，再从云端更新");
        }
        if (sessionService.onDraftBranch(projectId)) {
            throw VersionException.userFacing("请先回到主线工作，再从云端更新");
        }
    }

    /** 冲突窗口 payload，形状同 VersionController.cloudConflictStatus——两处独立反查，不共享代码是故意的（一个在写入时机知道，一个在 /status 轮询时反查）。 */
    private Map<String, Object> cloudConflictPayload(long projectId) {
        Map<String, Object> m = new HashMap<>();
        m.put("conflictingPaths", WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId)));
        m.put("mainlineTip", repoService.resolveRef(projectId, "HEAD"));
        m.put("cloudTip", repoService.mergeHeadRef(projectId));
        return m;
    }

    private CloudConnection connectionOf(ProjectRemote remote) {
        return connectionRepository.findById(remote.getConnectionId())
                .orElseThrow(() -> new VersionException("云端连接不存在: " + remote.getConnectionId()));
    }

    /** 合并提交的作者邮箱。userId 为 null（uploadToCloud 的自动整合无用户上下文）时退化为账号名。 */
    private String authorEmail(Long userId, String userName) {
        return userId != null
                ? "user-" + userId + "@aiworkdeck.local"
                : userName + "@aiworkdeck.local";
    }

    /** 无需认证头的调用，委托三参版本。 */
    protected String httpPost(String url, String jsonBody) {
        return httpPost(url, jsonBody, null);
    }

    /** 单测覆写此 seam 打桩（PluginMarketService.httpGet 同款约定）。sessionToken 非空时带 X-Session-Id 头。 */
    protected String httpPost(String url, String jsonBody, String sessionToken) {
        HttpRequest req = HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .body(jsonBody)
                .setConnectionTimeout(5000)
                .setReadTimeout(15000);
        if (sessionToken != null) {
            req.header("X-Session-Id", sessionToken);
        }
        try (HttpResponse resp = req.execute()) {
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
