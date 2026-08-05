package com.checkba.version;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ProjectRepository projectRepository;

    public CloudSyncService(ProjectRepoService repoService,
                             WorkSessionService sessionService,
                             ProjectTreeManifestService manifestService,
                             ProjectFileRepository fileRepository,
                             CloudConnectionRepository connectionRepository,
                             ProjectRemoteRepository remoteRepository,
                             ProjectRepository projectRepository) {
        this.repoService = repoService;
        this.sessionService = sessionService;
        this.manifestService = manifestService;
        this.fileRepository = fileRepository;
        this.connectionRepository = connectionRepository;
        this.remoteRepository = remoteRepository;
        this.projectRepository = projectRepository;
    }

    /** CONFLICT（Task 9）：被拒后自动合并遇到冲突，仓库停在 MERGING 等裁决（同云端更新的冲突窗口）。 */
    public enum UploadStatus { UPLOADED, REMOTE_AHEAD, OFFLINE_PENDING, NOT_LINKED, CONFLICT }

    /** affectedFileIds：前台上传触发自动整合时，整合改写的文件 id（重载链用）；其余路径恒空列表。 */
    public record UploadResult(UploadStatus status, String message, List<Long> affectedFileIds) {
        public UploadResult(UploadStatus status, String message) {
            this(status, message, List.of());
        }
    }

    public enum UpdateStatus { UP_TO_DATE, UPDATED, CONFLICT, OFFLINE, NOT_LINKED }

    public record UpdateResult(UpdateStatus status, List<Long> affectedFileIds,
                               Map<String, Object> conflict) {}

    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static final String CLOUD_MERGE_TITLE = "取回最新稿";
    private static final String CLOUD_SIDE_LABEL = "团队案件库";

    /** 用账号密码换一个长期设备令牌，本地存下来（服务端 `/api/auth/device-token`）。 */
    public CloudConnection connect(String serverUrl, String username,
                                    String password, String deviceName, Long userId) {
        String base = serverUrl.replaceAll("/+$", "");
        String body = JSONUtil.toJsonStr(Map.of(
                "username", username, "password", password, "name", deviceName));
        JSONObject resp = JSONUtil.parseObj(httpPost(base + "/api/auth/device-token", body));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing("连不上团队案件库：" + resp.getStr("message", "账号或密码不对"));
        }
        JSONObject data = resp.getJSONObject("data");
        CloudConnection conn = new CloudConnection();
        conn.setUserId(userId);
        conn.setServerUrl(base);
        conn.setUsername(data.getStr("username"));
        conn.setDisplayName(data.getStr("displayName"));
        conn.setDeviceToken(data.getStr("token"));
        conn.setTokenId(data.getLong("tokenId", null));
        conn.setCreatedAt(LocalDateTime.now());
        return connectionRepository.save(conn);
    }

    /** 断开一个云端连接：尽力撤远端令牌 + 删本地连接与所有关联的项目绑定。 */
    public void disconnect(long connectionId, Long userId) {
        connectionRepository.findById(connectionId).filter(c -> ownedBy(c, userId)).ifPresent(conn -> {
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

    public java.util.List<CloudConnection> listConnections(Long userId) {
        return connectionRepository.findByUserId(userId);
    }

    /**
     * 连接里存着长期设备令牌，等同于归属人在云端的身份：多人共用一个后端时，
     * 只要能引用别人的 connectionId 就能借他的令牌列/克隆对方的云端项目。
     * 归属为空的旧行（本列上线前建的）一律当作不可用，重新连接一次即可。
     */
    private boolean ownedBy(CloudConnection conn, Long userId) {
        return userId != null && userId.equals(conn.getUserId());
    }

    /** 找不到与不归属一律用同一句话，避免成为连接是否存在的探测口。 */
    private CloudConnection ownedConnection(long connectionId, Long userId) {
        return connectionRepository.findById(connectionId)
                .filter(c -> ownedBy(c, userId))
                .orElseThrow(() -> new VersionException("云端连接不存在: " + connectionId));
    }

    // ==================== 共享上云 / 从云端接入（Task 10） ====================

    /**
     * 把一个还没共享过的本地项目上云：服务端建一个新项目 → prepare-remote（建空仓等首推）→
     * 本地配好 origin → 首推整段历史（不同于日常上传，这里 master 在服务端是分支新建，
     * WorkSessionService.ingestPushedMainline 走 zeroId 全量物化分支）。
     * 守卫：项目未共享过；本地已开版本记录（否则律师看不懂"共享"是什么意思）；
     * 仓库不在合并窗口中（同上传/更新的既有纪律）。
     */
    public Map<String, Object> shareToCloud(long projectId, long connectionId, Long userId) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            if (remoteRepository.findByProjectId(projectId).isPresent()) {
                throw VersionException.userFacing("这份案卷已经在团队案件库里了");
            }
            if (!repoService.isInitialized(projectId)) {
                throw VersionException.userFacing("请先开启版本记录，再放进团队案件库");
            }
            if (repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("请先把等你做选择的文件处理完");
            }
            CloudConnection conn = ownedConnection(connectionId, userId);
            String localName = projectRepository.findById(projectId)
                    .map(Project::getName).orElse("未命名项目");

            String createBody = JSONUtil.toJsonStr(Map.of("projectType", "BLANK", "name", localName));
            JSONObject created = JSONUtil.parseObj(
                    httpPost(conn.getServerUrl() + "/api/projects", createBody, conn.getDeviceToken()));
            long remoteProjectId = created.getLong("id");

            // 服务端项目已经建好：往后任何一步失败都会留下云端孤儿项目，整段包
            // try/catch 补偿删除（尽力而为，删除失败只 log.warn），再原样重抛。
            try {
                JSONObject prep = JSONUtil.parseObj(httpPost(
                        conn.getServerUrl() + "/api/projects/" + remoteProjectId + "/version/prepare-remote",
                        "{}", conn.getDeviceToken()));
                if (prep.getInt("code", 1) != 0) {
                    throw VersionException.userFacing("没能放进团队案件库：" + prep.getStr("message", "请重试"));
                }

                repoService.setRemoteOrigin(projectId, conn.getServerUrl() + "/git/" + remoteProjectId + ".git");
                ProjectRepoService.PushOutcome out = repoService.pushMainlineToOrigin(
                        projectId, conn.getUsername(), conn.getDeviceToken());
                if (!out.pushed()) {
                    throw new VersionException("首推云端失败: project=" + projectId + " " + out.message());
                }

                ProjectRemote remote = new ProjectRemote();
                remote.setProjectId(projectId);
                remote.setConnectionId(connectionId);
                remote.setRemoteProjectId(String.valueOf(remoteProjectId));
                remote.setPendingUpload(false);
                remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                remote.setCreatedAt(LocalDateTime.now());
                remoteRepository.save(remote);

                Map<String, Object> result = new HashMap<>();
                result.put("remoteProjectId", remoteProjectId);
                return result;
            } catch (RuntimeException e) {
                deleteRemoteProjectBestEffort(conn, remoteProjectId);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 共享上云半途失败的补偿：尽力删掉刚建好的云端孤儿项目，删除失败只 log.warn，不掩盖原异常。 */
    private void deleteRemoteProjectBestEffort(CloudConnection conn, long remoteProjectId) {
        try {
            JSONObject resp = JSONUtil.parseObj(httpDelete(
                    conn.getServerUrl() + "/api/projects/" + remoteProjectId, conn.getDeviceToken()));
            if (resp.getInt("code", 1) != 0) {
                log.warn("补偿删除云端孤儿项目未成功: remoteProjectId={}, message={}",
                        remoteProjectId, resp.getStr("message"));
            }
        } catch (Exception e) {
            log.warn("补偿删除云端孤儿项目失败: remoteProjectId={}", remoteProjectId, e);
        }
    }

    /**
     * 从云端接入一个项目：prepare-remote（若云端还是 v1 清单，服务端顺带落一笔升级提交）→
     * 本地建一个新项目行 → 整仓克隆 → 读 HEAD 清单落库。清单必须是 v2——v1 清单里的节点
     * 没有跨机器一致的 uid，v2 是本机制的立身之本，旧格式一律拒绝、指引律师先在云端更新一次。
     */
    public Map<String, Object> cloneFromCloud(long connectionId, long remoteProjectId, Long localUserId) {
        CloudConnection conn = ownedConnection(connectionId, localUserId);

        JSONObject prep = JSONUtil.parseObj(httpPost(
                conn.getServerUrl() + "/api/projects/" + remoteProjectId + "/version/prepare-remote",
                "{}", conn.getDeviceToken()));
        if (prep.getInt("code", 1) != 0) {
            throw VersionException.userFacing("没能取到本机：" + prep.getStr("message", "请重试"));
        }

        String remoteName = listRemoteProjects(connectionId, localUserId).stream()
                .filter(m -> m.get("id") != null)
                .filter(m -> remoteProjectId == ((Number) m.get("id")).longValue())
                .map(m -> (String) m.get("name"))
                .findFirst()
                .orElse("案件库里的案卷");

        Project project = new Project();
        project.setName(remoteName);
        project.setProjectType("BLANK");
        project.setListedCompanyName("");
        project.setTargetCompanyName("");
        project.setUserId(localUserId);
        project.setCreatedAt(LocalDateTime.now());
        project = projectRepository.save(project);
        long localProjectId = project.getId();

        ReentrantLock lock = sessionService.repoLock(localProjectId);
        lock.lock();
        try {
            // 本地 Project 行已经落库：往后任何一步失败都会留下打不开的幽灵项目
            // （DB 行 + 磁盘目录），整段包 try/catch 补偿清理，再原样重抛。
            try {
                repoService.cloneFromRemote(localProjectId,
                        conn.getServerUrl() + "/git/" + remoteProjectId + ".git",
                        conn.getUsername(), conn.getDeviceToken());

                TreeManifest manifest = manifestService.readAtRef(localProjectId, "HEAD");
                if (manifest == null || manifest.version() < 2) {
                    throw VersionException.userFacing("这份案卷在案件库里还是旧格式，请让共享它的人先交一次稿，再来取");
                }
                manifestService.applyToDatabase(localProjectId, manifest);

                ProjectRemote remote = new ProjectRemote();
                remote.setProjectId(localProjectId);
                remote.setConnectionId(connectionId);
                remote.setRemoteProjectId(String.valueOf(remoteProjectId));
                remote.setPendingUpload(false);
                remote.setLastSyncSha(repoService.resolveRef(localProjectId, repoService.mainBranch()));
                remote.setCreatedAt(LocalDateTime.now());
                remoteRepository.save(remote);

                Map<String, Object> result = new HashMap<>();
                result.put("localProjectId", localProjectId);
                return result;
            } catch (RuntimeException e) {
                cleanupFailedClone(localProjectId, project);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 接入失败留下的本地半成品：删本地 Project 行 + 递归删 gitDir/workTree，清理失败只 log.warn，不掩盖原异常。 */
    private void cleanupFailedClone(long localProjectId, Project project) {
        try {
            projectRepository.delete(project);
        } catch (Exception e) {
            log.warn("接入失败清理本地项目行失败: project={}", localProjectId, e);
        }
        deleteDirectoryQuietly(localProjectId, repoService.gitDir(localProjectId));
        deleteDirectoryQuietly(localProjectId, repoService.workTree(localProjectId));
    }

    private void deleteDirectoryQuietly(long projectId, Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("接入失败清理目录失败: project={}, path={}", projectId, p, e);
                }
            });
        } catch (Exception e) {
            log.warn("接入失败清理目录失败: project={}, dir={}", projectId, dir, e);
        }
    }

    /** 某个云端连接下、本账号能看到的全部项目——{id, name, projectType} 透传，供"从云端接项目"选择列表用。 */
    public List<Map<String, Object>> listRemoteProjects(long connectionId, Long userId) {
        CloudConnection conn = ownedConnection(connectionId, userId);
        String body = httpGet(conn.getServerUrl() + "/api/projects/my", conn.getDeviceToken());
        List<Map<String, Object>> out = new ArrayList<>();
        int skipped = 0;
        for (Object o : JSONUtil.parseArray(body)) {
            JSONObject j = (JSONObject) o;
            Long id = j.getLong("id");
            if (id == null) {
                skipped++;
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("name", j.getStr("name"));
            m.put("projectType", j.getStr("projectType"));
            out.add(m);
        }
        if (skipped > 0) {
            log.warn("云端项目列表中有 {} 条缺少 id 的脏数据，已跳过: connection={}", skipped, connectionId);
        }
        return out;
    }

    /**
     * 推主线（含里程碑标签）到云端。被拒（PushOutcome.rejected）曾经统一按 REMOTE_AHEAD
     * 归类置黄灯（Task 8），Task 9 升级：**前台**（background=false）且守卫允许时（无
     * ACTIVE 工作段、不在稿上——仓库是否在合并中已经在上面单独判过）自动走
     * {@link #integrateFromCloud} 同一条内核，干净合并/快进后重推成功才算 UPLOADED
     * （整合改写的文件 id 随 affectedFileIds 带回，重载链用），遇到真实内容冲突则
     * UploadStatus.CONFLICT 让仓库停在裁决窗口；守卫不允许（有未收尾的工作/站在稿上）
     * 维持旧行为：只置 pendingUpload，等律师自己处理完再手动上传或从云端更新。
     *
     * **后台路径（background=true，含结束工作的 onMainlineMerged 自动上传）被拒时一律
     * 不自动整合**，只置 pendingUpload（remoteAhead 灯自然亮起，等律师前台点「立即上传」）。
     * 两条理由（v2 终审 I2）：① 后台整合会改写磁盘，却没有任何通道通知打开中的编辑器
     * 重载（v1 地雷 #11 的 autosave 覆盖形态——编辑器把整合前的旧字节写回，整合结果被
     * 静默冲掉）；② 后台整合撞上冲突会开出一个律师不知情的 MERGING 裁决窗口。
     *
     * background 没有真实用户上下文，前台自动合并需要的提交作者身份
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
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "请先把等你做选择的文件处理完");
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
                if (!background && canAutoIntegrate(projectId)) {
                    String authorName = conn.getDisplayName() != null ? conn.getDisplayName() : conn.getUsername();
                    UpdateResult integrated = integrateFromCloud(projectId, conn, null, authorName);
                    if (integrated.status() == UpdateStatus.UPDATED
                            || integrated.status() == UpdateStatus.UP_TO_DATE) {
                        return new UploadResult(UploadStatus.UPLOADED, null,
                                integrated.affectedFileIds());
                    }
                    if (integrated.status() == UpdateStatus.CONFLICT) {
                        remote.setPendingUpload(true);
                        remoteRepository.save(remote);
                        // 不说「同一处」：文档类是整份字节比对，两边都动过就整份进裁决清单，
                        // 说成同一处会让律师低估选错一边的代价（口径同 AdoptConflictDialog.hintText）。
                        return new UploadResult(UploadStatus.CONFLICT, "有几份文件同事也改过，需要你选一下整份留哪一边");
                    }
                    // 走到这里只剩 OFFLINE（fetch 联不上）：整合本身没能进行，落回
                    // 旧行为。integrateFromCloud 直接复用这里已经解析好的 conn、
                    // 不会再走一次 remoteOpt 查找，NOT_LINKED 不会从这条路径出现。
                }
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "同事交了新稿，先结束手头这段工作再交稿");
            } catch (VersionException e) {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                log.warn("上传/整合失败: project={}", projectId, e);
                if (background) {
                    return new UploadResult(UploadStatus.OFFLINE_PENDING, null);
                }
                throw VersionException.userFacing("这次没能交稿，改动已经记下，稍后还可以再交");
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

    /**
     * 联网检查一次云端状态：fetch 最新 origin/master → 回 cloudStatus（remoteAhead 会反映
     * fetch 后的结果）。云端不可达是正常场景（黄灯态本就允许离线）——绝不抛，只在结果里
     * 多带一个 offline:true，cloudStatus 原有字段照常给（沿用 fetch 之前已知的状态）。
     */
    public Map<String, Object> checkCloud(long projectId) {
        var remoteOpt = remoteRepository.findByProjectId(projectId);
        if (remoteOpt.isEmpty()) {
            return Map.of("linked", false);
        }
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            // 合并窗口期间不 fetch（v2 终审 I3）：fetch 会推进 origin/master，而窗口判定
            // 靠「MERGE_HEAD 等于/是 origin/master 的祖先」反查——旧的相等判定下 fetch 一次
            // 就把开着的冲突窗口孤儿化（三语境都对不上号，弹窗消失、裁决端点全拒）。
            // 状态照常给本地快照，多带 merging:true。
            if (repoService.repositoryMerging(projectId)) {
                Map<String, Object> m = new HashMap<>(cloudStatus(projectId));
                m.put("merging", true);
                return m;
            }
            CloudConnection conn = connectionOf(remoteOpt.get());
            try {
                repoService.fetchFromOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
            } catch (Exception e) {
                log.warn("云端状态检查 fetch 失败，仅回退为离线态: project={}", projectId, e);
                Map<String, Object> m = new HashMap<>(cloudStatus(projectId));
                m.put("offline", true);
                return m;
            }
            return cloudStatus(projectId);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 成员桌面代理（spec 第六节） ====================

    /** 未关联云端时两个代理端点共用的守卫：引导律师先共享。 */
    private ProjectRemote requireRemoteBinding(long projectId) {
        return remoteRepository.findByProjectId(projectId)
                .orElseThrow(() -> VersionException.userFacing("请先把这份案卷放进团队案件库"));
    }

    /** 透传服务端 {@code GET /api/projects/{rid}/members}——{id, userId, role, joinedAt, username, displayName, avatarUrl} 原样带回。 */
    public List<Map<String, Object>> proxyMembers(long projectId) {
        ProjectRemote remote = requireRemoteBinding(projectId);
        CloudConnection conn = connectionOf(remote);
        JSONObject resp = JSONUtil.parseObj(httpGet(
                conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId() + "/members",
                conn.getDeviceToken()));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing("读取案件参与人失败：" + resp.getStr("message", "请重试"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : resp.getJSONArray("data")) {
            out.add((JSONObject) o);
        }
        return out;
    }

    /** 透传服务端加成员端点，role 缺省 PARTICIPANT（由调用方决定，这里只透传）。 */
    public void proxyMembers(long projectId, String username, String role) {
        ProjectRemote remote = requireRemoteBinding(projectId);
        CloudConnection conn = connectionOf(remote);
        String body = JSONUtil.toJsonStr(Map.of("username", username, "role", role));
        JSONObject resp = JSONUtil.parseObj(httpPost(
                conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId() + "/members",
                body, conn.getDeviceToken()));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing("没能把人加进来：" + resp.getStr("message", "请重试"));
        }
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
                throw VersionException.userFacing("现在没有等你做选择的文件");
            }
            String cloudTip = repoService.mergeHeadRef(projectId);
            requireCloudMergeWindow(projectId, cloudTip);
            List<String> rawConflicts = repoService.conflictingPaths(projectId);
            if (rawConflicts.isEmpty()) {
                throw new VersionException("冲突记录已丢失，无法安全完成更新: project=" + projectId);
            }
            String mainTip = repoService.resolveRef(projectId, "HEAD");
            List<String> conflicts = WorkSessionService.userVisibleConflicts(rawConflicts);
            Map<String, WorkSessionService.Resolution> choices =
                    resolutions == null ? Map.of() : resolutions;
            for (String path : conflicts) {
                if (choices.get(path) == null) throw VersionException.userFacing("还有文件没选留哪一份");
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
                throw VersionException.userFacing("现在没有等你做选择的文件");
            }
            requireCloudMergeWindow(projectId, repoService.mergeHeadRef(projectId));
            repoService.abortMerge(projectId);
            return "这次没有取回，你的内容分毫未动";
        } finally {
            lock.unlock();
        }
    }

    /**
     * 干净合并或裁决后的云端合并统一收尾：清单并集（并集语义同采纳，地雷 #21——干净路径
     * 与冲突裁决路径必须以同一种方式提交，清单与内容进同一个双亲提交；带三方基线，见
     * {@link ProjectTreeManifestService#unionApply(long, TreeManifest, TreeManifest)}——
     * 基线是合并前本地 tip 与云端 tip 的合并基线，只有云端那一侧相对基线真的做过复活
     * 动作才会复活本方亲手软删的文件）→ 提交 → 自动重推。
     * 重推失败（网络问题）不回滚——合并已经落地，只是回传没成，转入待上传，绝不丢改动。
     */
    private UpdateResult completeCloudMerge(long projectId, String tipBefore, String cloudTip,
                                            CloudConnection conn, Long userId, String userName) {
        var cloudManifest = manifestService.readAtRef(projectId, cloudTip);
        String baseSha = repoService.mergeBase(projectId, tipBefore, cloudTip);
        TreeManifest base = baseSha == null ? null : manifestService.readAtRef(projectId, baseSha);
        if (cloudManifest != null) manifestService.unionApply(projectId, cloudManifest, base);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        repoService.commitMergeResolution(projectId, CLOUD_MERGE_TITLE,
                userName, authorEmail(userId, userName));
        try {
            // 重推被拒是返回值不是异常（裁决窗口期间远端又被同事推进了一版）：不接住的话
            // 会绿灯假同步——pendingUpload=false + lastSyncSha=本地 sha，界面显示「已与云端
            // 同步」而云端根本没有这份裁决结果（v2 终审 I1）。
            ProjectRepoService.PushOutcome out = repoService.pushMainlineToOrigin(
                    projectId, conn.getUsername(), conn.getDeviceToken());
            remoteRepository.findByProjectId(projectId).ifPresent(remote -> {
                if (out.pushed()) {
                    remote.setPendingUpload(false);
                    remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                } else {
                    log.warn("云端更新合并已落地，重推被拒，转入待上传: project={}, {}",
                            projectId, out.message());
                    remote.setPendingUpload(true);
                }
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

    /**
     * 云端合并窗口判定（v2 终审 I3）：窗口语境以**开窗时刻**的 MERGE_HEAD 为准——窗口期间
     * 远端被同事推进、origin/master 前移，不该把开着的窗口孤儿化，所以判定从「MERGE_HEAD
     * 等于 origin/master」放宽为「相等或是 origin/master 的祖先」。判定顺序与 /status 的
     * 三语境链一致：先排除结束工作撞车（活动段 tip 精确相等优先），再做云端侧的祖先判定；
     * 稿采纳窗口的 MERGE_HEAD（稿 tip 带着从未推送的提交）天然不在 origin 历史里，落不进
     * 祖先判定。守卫失败即「正在处理的是另一件事」——不能无条件放行，误放会把别的语境的
     * 合并窗口当云端更新收尾/销毁。
     */
    private void requireCloudMergeWindow(long projectId, String mergeHead) {
        if (mergeHead == null) {
            throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
        }
        var active = sessionService.activeSession(projectId);
        if (active.isPresent() && mergeHead.equals(
                repoService.resolveRef(projectId, active.get().getBranchName()))) {
            throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
        }
        String originSha = repoService.originMasterSha(projectId);
        boolean cloudWindow = mergeHead.equals(originSha)
                || (originSha != null && repoService.isAncestor(projectId, mergeHead, ORIGIN_MASTER));
        if (!cloudWindow) {
            throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
        }
    }

    /** updateFromCloud 的前置守卫：没有进行中的合并/工作/稿，理由同采纳前置（避免几件事缠在一起）。 */
    private void requireCleanForCloudOps(long projectId) {
        if (repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing("请先把等你做选择的文件处理完");
        }
        if (sessionService.activeSession(projectId).isPresent()) {
            throw VersionException.userFacing("请先结束或丢弃手头这段工作，再取回最新稿");
        }
        if (sessionService.onDraftBranch(projectId)) {
            throw VersionException.userFacing("请先回到主线工作，再取回最新稿");
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

    /** 单测覆写此 seam 打桩，形状同 httpPost 的三参版本。sessionToken 非空时带 X-Session-Id 头。 */
    protected String httpGet(String url, String sessionToken) {
        HttpRequest req = HttpRequest.get(url)
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

    /** 单测覆写此 seam 打桩，形状同 httpPost 的三参版本。sessionToken 非空时带 X-Session-Id 头。 */
    protected String httpDelete(String url, String sessionToken) {
        HttpRequest req = HttpRequest.delete(url)
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
