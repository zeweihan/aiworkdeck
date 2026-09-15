// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LangText;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 提交署名解析（spec 2026-09-14 §2.1）。**字段注入不是构造器参数**：本类的构造器被
     * 五个单测手工 new，加参数是纯 churn。required=false，authorEmail 自带回落。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VersionAuthorResolver authorResolver;

    /** 单测用：走字段注入，手工 new 出来的实例得有地方补上。 */
    void setAuthorResolverForTest(VersionAuthorResolver resolver) {
        this.authorResolver = resolver;
    }

    /**
     * 本机连着的官网账户（{@link #ensureRemoteUserId} 回填 remoteUserId 要用它做判据）。
     * 同样字段注入，理由同上；required=false，自建服务器上没有官网账户也照常跑。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.service.account.AccountService accountService;

    /** 单测用：同上。 */
    void setAccountServiceForTest(com.checkba.service.account.AccountService service) {
        this.accountService = service;
    }

    /**
     * 三方合并（spec 2026-09-14 §4.3/§4.4）：冲突窗口 payload 里的
     * {@code mergeBase}/{@code documentMerges}/{@code sides} 由分析服务统一拼，
     * 逐处合好的文件由待决记录佐证。同样字段注入、{@code required=false}——
     * 手工 new 出本服务的那几个单测不关心这一档，缺席时冲突窗口就是 v2 的老形状。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.version.merge.MergeAnalysisService mergeAnalysisService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.version.merge.PendingMergeStore pendingMergeStore;

    /** 单测/跨包装配用：走字段注入，手工 new 出来的实例得有地方补上。 */
    public void setMergeServicesForTest(com.checkba.version.merge.MergeAnalysisService analysis,
                                        com.checkba.version.merge.PendingMergeStore store) {
        this.mergeAnalysisService = analysis;
        this.pendingMergeStore = store;
    }

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

    /**
     * landedOnCloud：这次整合的结果是不是已经到了云端。真合并路径要看
     * {@link #completeCloudMerge} 的重推有没有落地；快进/已最新两条路径压根不用推
     * （本地本就是云端的祖先），天然为真——所以缺省构造器给 true，别拿
     * ProjectRemote.pendingUpload 的历史值反推，会把本就同步的项目误判成失败。
     */
    public record UpdateResult(UpdateStatus status, List<Long> affectedFileIds,
                               Map<String, Object> conflict, boolean landedOnCloud) {
        public UpdateResult(UpdateStatus status, List<Long> affectedFileIds,
                            Map<String, Object> conflict) {
            this(status, affectedFileIds, conflict, true);
        }
    }

    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static String cloudMergeTitle() {
        return LangText.of("取回最新稿", "Pull Latest");
    }

    private static String cloudSideLabel() {
        return LangText.of("团队案件库", "Team Case Library");
    }

    /** 用账号密码换一个长期设备令牌，本地存下来（服务端 `/api/auth/device-token`）。 */
    public CloudConnection connect(String serverUrl, String username,
                                    String password, String deviceName, Long userId) {
        String base = serverUrl.replaceAll("/+$", "");
        String body = JSONUtil.toJsonStr(Map.of(
                "username", username, "password", password, "name", deviceName));
        JSONObject resp = JSONUtil.parseObj(httpPost(base + "/api/auth/device-token", body));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "连不上团队案件库：" + resp.getStr("message", "账号或密码不对"),
                    "Couldn't connect to the Team Case Library: " + resp.getStr("message", "incorrect username or password")));
        }
        JSONObject data = resp.getJSONObject("data");
        CloudConnection conn = new CloudConnection();
        conn.setUserId(userId);
        conn.setServerUrl(base);
        conn.setUsername(data.getStr("username"));
        conn.setDisplayName(data.getStr("displayName"));
        conn.setDeviceToken(data.getStr("token"));
        conn.setTokenId(data.getLong("tokenId", null));
        // 案件库那一侧的 userId：协作事件行判「这条是不是我干的」只能靠它
        conn.setRemoteUserId(data.getLong("userId", null));
        conn.setCreatedAt(LocalDateTime.now());
        return connectionRepository.save(conn);
    }

    /** 断开一个云端连接：尽力撤远端令牌 + 删本地连接与所有关联的项目绑定。 */
    public void disconnect(long connectionId, Long userId) {
        connectionRepository.findById(connectionId).filter(c -> ownedBy(c, userId)).ifPresent(conn -> {
            revokeRemoteToken(conn.getServerUrl(), conn.getTokenId(), conn.getDeviceToken());
            remoteRepository.findByConnectionId(connectionId).forEach(remoteRepository::delete);
            connectionRepository.delete(conn);
        });
    }

    /**
     * 尽力撤掉远端一枚长期设备令牌：断开连接、官方连接重桥换令牌之后共用。失败只记日志——
     * 撤不掉最多是远端多一枚不再有人用的令牌，不值得为它让断开或改名失败。
     */
    void revokeRemoteToken(String serverUrl, Long tokenId, String sessionToken) {
        if (tokenId == null) return;
        try {
            JSONObject resp = JSONUtil.parseObj(httpPost(
                    serverUrl + "/api/auth/device-token/" + tokenId + "/revoke", "{}", sessionToken));
            if (resp.getInt("code", 1) != 0) {
                log.warn("远端撤销设备令牌未成功: server={}, tokenId={}, message={}",
                        serverUrl, tokenId, resp.getStr("message"));
            }
        } catch (Exception e) {
            log.warn("远端撤销设备令牌失败: server={}, tokenId={}", serverUrl, tokenId, e);
        }
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
                throw VersionException.userFacing(LangText.of("这份案卷已经在团队案件库里了", "This case file is already in the Team Case Library"));
            }
            if (!repoService.isInitialized(projectId)) {
                throw VersionException.userFacing(LangText.of("请先开启版本记录，再放进团队案件库", "Please enable version history before adding this to the Team Case Library"));
            }
            if (repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing(LangText.of("请先把等你做选择的文件处理完", "Please finish choosing your files first"));
            }
            CloudConnection conn = ownedConnection(connectionId, userId);
            String localName = projectRepository.findById(projectId)
                    .map(Project::getName).orElse(LangText.of("未命名项目", "Untitled Project"));

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
                    throw VersionException.userFacing(LangText.of(
                            "没能放进团队案件库：" + prep.getStr("message", "请重试"),
                            "Couldn't add this to the Team Case Library: " + prep.getStr("message", "please try again")));
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

        // 换机器取回的查重：这份案卷本机已经有了就把既有的本机 id 还回去，不再造第二个
        // ——两份各带一个 origin 绑定，律师会在两个项目里各改一半而谁也不知道另一半的存在。
        var alreadyHere = remoteRepository.findByConnectionIdAndRemoteProjectId(
                connectionId, String.valueOf(remoteProjectId));
        if (alreadyHere.isPresent()) {
            Map<String, Object> existing = new HashMap<>();
            existing.put("localProjectId", alreadyHere.get().getProjectId());
            existing.put("alreadyLocal", true);
            return existing;
        }

        JSONObject prep = JSONUtil.parseObj(httpPost(
                conn.getServerUrl() + "/api/projects/" + remoteProjectId + "/version/prepare-remote",
                "{}", conn.getDeviceToken()));
        if (prep.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "没能取到本机：" + prep.getStr("message", "请重试"),
                    "Couldn't pull this to your machine: " + prep.getStr("message", "please try again")));
        }

        String remoteName = listRemoteProjects(connectionId, localUserId).stream()
                .filter(m -> m.get("id") != null)
                .filter(m -> remoteProjectId == ((Number) m.get("id")).longValue())
                .map(m -> (String) m.get("name"))
                .findFirst()
                .orElse(LangText.of("案件库里的案卷", "A case file in the library"));

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
                    throw VersionException.userFacing(LangText.of(
                            "这份案卷在案件库里还是旧格式，请让共享它的人先交一次稿，再来取",
                            "This case file is still in an old format in the library — please have whoever shared it submit a draft first, then pull again"));
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
            // 「取一份案卷」的列表里显示我在这份案卷里是什么身份（ProjectCardDTO 现成字段）：
            // 被邀请进来的人在取之前就该看得见自己是协作人还是只读。
            m.put("myRole", j.getStr("myRole"));
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
                return new UploadResult(UploadStatus.REMOTE_AHEAD, LangText.of("请先把等你做选择的文件处理完", "Please finish choosing your files first"));
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
                        if (!integrated.landedOnCloud()) {
                            // 合并在本地落地了，回传却没到云端（重推又被拒/网络断）：报
                            // UPLOADED 就是把真失败说成成功——界面显示「已交稿」，而同事
                            // 永远看不到这份合并。黄灯已由 completeCloudMerge 置上，这里
                            // 只说实话；affectedFileIds 照常带回，整合改写过的磁盘仍要走
                            // 重载链。
                            return new UploadResult(UploadStatus.OFFLINE_PENDING,
                                    LangText.of("这次没能交稿，改动已经记下，稍后还可以再交", "This submission didn't go through — your changes are saved and you can submit again later"),
                                    integrated.affectedFileIds());
                        }
                        return new UploadResult(UploadStatus.UPLOADED, null,
                                integrated.affectedFileIds());
                    }
                    if (integrated.status() == UpdateStatus.CONFLICT) {
                        remote.setPendingUpload(true);
                        remoteRepository.save(remote);
                        // 不说「同一处」：文档类是整份字节比对，两边都动过就整份进裁决清单，
                        // 说成同一处会让律师低估选错一边的代价（口径同 AdoptConflictDialog.hintText）。
                        return new UploadResult(UploadStatus.CONFLICT, LangText.of("有几份文件同事也改过，需要你选一下整份留哪一边", "A colleague also edited a few files — please pick which version to keep for each"));
                    }
                    // 走到这里只剩 OFFLINE（fetch 联不上）：整合本身没能进行，落回
                    // 旧行为。integrateFromCloud 直接复用这里已经解析好的 conn、
                    // 不会再走一次 remoteOpt 查找，NOT_LINKED 不会从这条路径出现。
                }
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                return new UploadResult(UploadStatus.REMOTE_AHEAD, LangText.of("同事交了新稿，先结束手头这段工作再交稿", "A colleague submitted a new draft — please finish your current work session before submitting"));
            } catch (VersionException e) {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                log.warn("上传/整合失败: project={}", projectId, e);
                if (background) {
                    return new UploadResult(UploadStatus.OFFLINE_PENDING, null);
                }
                throw VersionException.userFacing(LangText.of("这次没能交稿，改动已经记下，稍后还可以再交", "This submission didn't go through — your changes are saved and you can submit again later"));
            }
        } finally {
            lock.unlock();
        }
    }

    /** 上传被拒后能否自动整合：仓库是否在合并中，调用方已经判过；这里只补活跃工作/稿两道守卫。 */
    private boolean canAutoIntegrate(long projectId) {
        return sessionService.activeSession(projectId).isEmpty() && !sessionService.onDraftBranch(projectId);
    }

    /**
     * 不联网的云端状态快照（/status 与云端状态区吃它）。
     *
     * <p>{@code userId} 只用来回答「这几版新稿是不是我自己在另一台电脑上交的」
     * （spec 2026-09-14 §2.5）：同一个官网账号在两台机器上桥接案件库落到**同一行**
     * app_users，远端提交的署名与本机一模一样，光靠 ref 比较得出的
     * {@code remoteAhead} 只能说出「有新稿」，说不出是谁的——界面于是对着自己
     * 昨晚在办公室交的稿说「同事交了新稿」。传 null 就退化成原来那份纯 ref 快照。
     */
    public Map<String, Object> cloudStatus(long projectId, Long userId) {
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
        if (remoteAhead) {
            describeRemoteAhead(projectId, userId, m);
        }
        return m;
    }

    /** 单参版本（userId 未知时的纯 ref 快照）。 */
    public Map<String, Object> cloudStatus(long projectId) {
        return cloudStatus(projectId, null);
    }

    // ========== 案件库展示名缓存（把 git 署名换成案件库账户的名字） ==========

    /**
     * 一个项目的「案件库账号名 → 展示名」快照。{@code expiresAt} 到点即失效；
     * **空表也会被缓存**——案件库连不上时不能让每一次 120 秒轮询都去重试一趟。
     */
    private record RemoteNames(Map<String, String> byUsername, long expiresAt) {}

    /** 缓存有效期。参与人改名是低频事件，十分钟内看到旧名字完全可以接受。 */
    static final long REMOTE_NAME_TTL_MS = 10 * 60 * 1000L;

    private final Map<Long, RemoteNames> remoteNameCache = new ConcurrentHashMap<>();

    /**
     * 案件库那边的「账号名 → 展示名」。版本行的 git 署名是**对方那台机器的本机展示名**
     * （单机模式下人人都叫「本机用户」），而事件行取的是案件库账户的展示名——
     * 同一屏里两种叫法。这张表就是把前者翻译成后者的字典。
     *
     * @param allowFetch 缓存里没有时允不允许打一趟请求。只有「案件库确实领先了、
     *                   此刻非说清是谁不可」的那条路传 true（{@link #describeRemoteAhead}）；
     *                   读历史/时间线一律传 false——列表渲染不该因为一个名字去联网。
     * @return 永不为 null；没有绑定案件库、取不到、或不许联网时是空表（调用方保持 git 署名）
     */
    public Map<String, String> remoteDisplayNames(long projectId, boolean allowFetch) {
        RemoteNames cached = remoteNameCache.get(projectId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.byUsername();
        }
        if (!allowFetch) return Map.of();
        return refreshRemoteDisplayNames(projectId);
    }

    /**
     * 真去案件库取一次参与人表并落缓存。整段吞异常：翻译不出名字只是让界面继续显示
     * git 署名（本列上线前的既有行为），不值得为它把云端状态或提交历史打成错误。
     */
    private Map<String, String> refreshRemoteDisplayNames(long projectId) {
        Map<String, String> names = Map.of();
        try {
            ProjectRemote remote = remoteRepository.findByProjectId(projectId).orElse(null);
            if (remote != null) {
                CloudConnection conn = connectionOf(remote);
                JSONObject resp = JSONUtil.parseObj(httpGet(
                        conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId() + "/members",
                        conn.getDeviceToken()));
                if (resp.getInt("code", 1) == 0) {
                    names = namesFrom(resp.getJSONArray("data"));
                }
            }
        } catch (Exception e) {
            log.warn("读取案件库参与人展示名失败（历史照常显示 git 署名）: project={}", projectId, e);
        }
        remoteNameCache.put(projectId,
                new RemoteNames(names, System.currentTimeMillis() + REMOTE_NAME_TTL_MS));
        return names;
    }

    /** 已经拿到手的参与人表顺手落进缓存（proxyMembers / ensureRemoteUserId 各调一次）。 */
    private void cacheRemoteDisplayNames(long projectId, JSONArray rows) {
        Map<String, String> names = namesFrom(rows);
        if (names.isEmpty()) return; // 空表只在「真去取了一趟」时才值得缓存，见 refresh
        remoteNameCache.put(projectId,
                new RemoteNames(names, System.currentTimeMillis() + REMOTE_NAME_TTL_MS));
    }

    private static Map<String, String> namesFrom(JSONArray rows) {
        if (rows == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Object o : rows) {
            if (!(o instanceof JSONObject row)) continue;
            String username = row.getStr("username");
            String display = row.getStr("displayName");
            if (username == null || username.isBlank()) continue;
            if (display == null || display.isBlank()) continue;
            out.put(username, display);
        }
        return out;
    }

    /** {@code master..origin/master} 最多走这么多版：状态条只需要计数与前三个名字。 */
    static final int REMOTE_AHEAD_WALK_CAP = 200;

    /** 状态条放得下的名字个数；总人数另走 {@code remoteAheadAuthorCount}。 */
    static final int REMOTE_AHEAD_NAME_CAP = 3;

    /** 作者去重时「本人」那一格的键。不用名字当键，免得同名的同事被并进来。 */
    private static final String SELF_AUTHOR_KEY = "\u0000self";

    /**
     * 往状态里补 {@code remoteAheadCount} / {@code remoteAheadAuthors} /
     * {@code remoteAheadAuthorCount} / {@code remoteAheadBySelf}。
     *
     * <p>名字最多给 {@value #REMOTE_AHEAD_NAME_CAP} 个（状态条只放得下这么多），但
     * {@code remoteAheadAuthorCount} 是**去重后的作者总数**——「张三等 N 人」里的 N
     * 要是拿名单长度算，四个人以上就永远说成 3 人。两个数字的量纲都受
     * {@link #REMOTE_AHEAD_WALK_CAP} 这一趟 walk 的上限约束。
     *
     * <p>算不出来只记日志、不抛也不填字段——这是一个**常驻的状态指示**，为了一句
     * 更准的话把整个云端状态接口打成 500，比显示那句笼统的「同事交了新稿」糟得多。
     */
    private void describeRemoteAhead(long projectId, Long userId, Map<String, Object> m) {
        try {
            List<VersionEntry> ahead = repoService.commitsBetween(
                    projectId, repoService.mainBranch(), ORIGIN_MASTER, REMOTE_AHEAD_WALK_CAP);
            if (ahead.isEmpty()) return;
            m.put("remoteAheadCount", ahead.size());
            // 案件库确实领先了、这句话非说清是谁不可——只有这条路允许为一个名字联网一次
            Map<String, String> remoteNames = remoteDisplayNames(projectId, true);
            // 「我是谁」整趟循环只算一次（这一趟最多 200 版）
            VersionAuthorResolver.SelfIdentity me = authorResolver != null && userId != null
                    ? authorResolver.selfIdentity(projectId, userId) : null;
            // 去重键 → 显示名。本人历史上用过几个署名都只占 SELF_AUTHOR_KEY 这一格（dev-board#647：
            // 旧实现按 git 署名去重，同一个人的三个年代的旧署名被数成三个同事）；哨兵键与
            // 真名分开，恰好与我同名的同事也不会被并进「本人」那一格。
            LinkedHashMap<String, String> distinct = new LinkedHashMap<>();
            boolean allSelf = me != null;
            for (VersionEntry e : ahead) {
                boolean self = me != null && authorResolver.isSelf(e, me);
                if (!self) allSelf = false;
                String name = VersionAuthorResolver.preferredAuthorName(
                        e, remoteNames, self ? me.displayName() : null);
                if (name == null || name.isBlank()) continue;
                distinct.putIfAbsent(self ? SELF_AUTHOR_KEY : name, name);
            }
            List<String> authors = new ArrayList<>();
            for (String name : distinct.values()) {
                if (authors.size() >= REMOTE_AHEAD_NAME_CAP) break;
                authors.add(name);
            }
            m.put("remoteAheadAuthors", authors);
            m.put("remoteAheadAuthorCount", distinct.size());
            // 「全部都是我」才算本人——只要掺进一版同事的，界面就该说同事的名字。
            m.put("remoteAheadBySelf", allSelf);
        } catch (Exception e) {
            log.warn("统计远端新稿的作者失败（状态照常给）: project={}", projectId, e);
        }
    }

    /**
     * 联网检查一次云端状态：fetch 最新 origin/master → 回 cloudStatus（remoteAhead 会反映
     * fetch 后的结果）。云端不可达是正常场景（黄灯态本就允许离线）——绝不抛，只在结果里
     * 多带一个 offline:true，cloudStatus 原有字段照常给（沿用 fetch 之前已知的状态）。
     */
    public Map<String, Object> checkCloud(long projectId, Long userId) {
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
                Map<String, Object> m = new HashMap<>(cloudStatus(projectId, userId));
                m.put("merging", true);
                return m;
            }
            CloudConnection conn = connectionOf(remoteOpt.get());
            try {
                repoService.fetchFromOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
            } catch (Exception e) {
                log.warn("云端状态检查 fetch 失败，仅回退为离线态: project={}", projectId, e);
                Map<String, Object> m = new HashMap<>(cloudStatus(projectId, userId));
                m.put("offline", true);
                return m;
            }
            return cloudStatus(projectId, userId);
        } finally {
            lock.unlock();
        }
    }

    /** 单参版本（userId 未知时的纯 ref 检查）。 */
    public Map<String, Object> checkCloud(long projectId) {
        return checkCloud(projectId, null);
    }

    // ==================== 成员桌面代理（spec 第六节） ====================

    /** 未关联云端时两个代理端点共用的守卫：引导律师先共享。 */
    private ProjectRemote requireRemoteBinding(long projectId) {
        return remoteRepository.findByProjectId(projectId)
                .orElseThrow(() -> VersionException.userFacing(LangText.of("请先把这份案卷放进团队案件库", "Please add this case file to the Team Case Library first")));
    }

    /**
     * 把 hutool 的 JSON 结构翻译成纯 Java 结构，供两个成员代理端点的返回值用。
     *
     * <p>hutool 把 JSON null 解析成 {@link cn.hutool.json.JSONNull} 单例，而 Jackson
     * 没有它的序列化器——原样当控制器返回值的话，只要上游某个字段是 null，整条响应
     * 就会 500（{@code No serializer found for class cn.hutool.json.JSONNull}）。
     * 而 {@code avatarUrl} 为 null 正是没绑官网/没传头像的默认状态，即绝大多数账号。
     */
    private static Object toPlain(Object v) {
        if (JSONUtil.isNull(v)) {
            return null;
        }
        if (v instanceof JSONObject o) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : o.keySet()) {
                m.put(k, toPlain(o.get(k)));
            }
            return m;
        }
        if (v instanceof JSONArray a) {
            List<Object> out = new ArrayList<>(a.size());
            for (Object item : a) {
                out.add(toPlain(item));
            }
            return out;
        }
        return v;
    }

    /** 透传服务端 {@code GET /api/projects/{rid}/members}——{id, userId, role, joinedAt, username, displayName, avatarUrl} 原样带回。 */
    public List<Map<String, Object>> proxyMembers(long projectId) {
        ProjectRemote remote = requireRemoteBinding(projectId);
        CloudConnection conn = connectionOf(remote);
        JSONObject resp = JSONUtil.parseObj(httpGet(
                conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId() + "/members",
                conn.getDeviceToken()));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "读取案件参与人失败：" + resp.getStr("message", "请重试"),
                    "Failed to load case members: " + resp.getStr("message", "please try again")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        JSONArray data = resp.getJSONArray("data");
        cacheRemoteDisplayNames(projectId, data);
        if (data != null) {
            for (Object o : data) {
                @SuppressWarnings("unchecked")
                Map<String, Object> plain = (Map<String, Object>) toPlain(o);
                if (plain != null) {
                    out.add(plain);
                }
            }
        }
        return out;
    }

    /**
     * 透传服务端查人端点（dev-board#444）：回 {@code {found, displayName, avatarUrl,
     * maskedContact, alreadyMember, currentRole, message}}。
     *
     * <p>{@code found:false} 是**正常结果**（服务端 code 仍是 0），原样带回给界面显示，
     * 不在这里翻译成异常——那会让「这个号还没人用过」弹成一个像故障的提示。
     */
    public Map<String, Object> proxyMemberLookup(long projectId, String identifier) {
        ProjectRemote remote = requireRemoteBinding(projectId);
        CloudConnection conn = connectionOf(remote);
        String url = conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId()
                + "/members/lookup?identifier="
                + java.net.URLEncoder.encode(identifier == null ? "" : identifier,
                        java.nio.charset.StandardCharsets.UTF_8);
        JSONObject resp = JSONUtil.parseObj(httpGet(url, conn.getDeviceToken()));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "没能找到这位同事：" + resp.getStr("message", "请重试"),
                    "Couldn't look up that colleague: " + resp.getStr("message", "please try again")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toPlain(resp.getJSONObject("data"));
        return data == null ? Map.of("found", false) : data;
    }

    /**
     * 透传案件库的协作事件（spec 2026-09-14 §2.3），外层补两个「我是谁」的字段：
     * {@code selfUserId} 是**案件库那一侧**的 userId（本机 userId 与事件表毫无关系），
     * {@code selfTokenId} 是本机这枚设备令牌——界面据此把事件行说成「你」「你（某台电脑）」
     * 还是同事的名字。本列上线前建的连接为空——{@link #ensureRemoteUserId} 在这里
     * 自动补一次（不必断开重连）；实在对不上才留 null，界面一律按「他人」渲染。
     */
    public Map<String, Object> proxyCollabEvents(long projectId, int limit, Long before) {
        ProjectRemote remote = requireRemoteBinding(projectId);
        CloudConnection conn = connectionOf(remote);
        ensureRemoteUserId(conn, remote);
        String url = conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId()
                + "/collab-events?limit=" + limit + (before == null ? "" : "&before=" + before);
        JSONObject resp = JSONUtil.parseObj(httpGet(url, conn.getDeviceToken()));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "读取协作记录失败：" + resp.getStr("message", "请重试"),
                    "Failed to load collaboration records: " + resp.getStr("message", "please try again")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toPlain(resp.getJSONObject("data"));
        Object events = data == null ? null : data.get("events");
        Map<String, Object> out = new HashMap<>();
        out.put("events", events instanceof List<?> list ? list : List.of());
        out.put("selfUserId", conn.getRemoteUserId());
        out.put("selfTokenId", conn.getTokenId());
        return out;
    }

    /**
     * 存量连接的 {@code remoteUserId} 自动回填（spec 2026-09-14 §2.3 收尾）。
     *
     * <p>这一列是本设计才加的，本列之前建的连接全是空——而事件表记的是**案件库那一侧**的
     * userId，空了就没法把「我自己干的那几行」认出来，律师会在提交历史里看到自己被当成同事。
     * 让他去断开重连太荒唐（重连要重桥、重发设备令牌），所以这里自动补：
     * 两侧 members 现在都带 {@code accountId}（官网账户 id，spec §2.6），拿本机连着的
     * 那个账户去案件库参与人列表里对一下就知道我是谁。
     *
     * <p>只挂在**协作事件代理**这一条路上，不挂 {@code cloudStatus}/{@code checkCloud}：
     * 那两个 120 秒轮询一次，为一件一次性的补写每两分钟多打一趟成员请求不值当。
     * 回填成功后 {@code remoteUserId} 非空，这个方法此后直接返回。
     *
     * <p>整段吞异常：认不出「我是谁」只是让事件行一律按他人渲染（本列上线前的既有行为），
     * 不值得为它把整个「提交历史」标签页打不开。
     */
    private void ensureRemoteUserId(CloudConnection conn, ProjectRemote remote) {
        if (conn.getRemoteUserId() != null || accountService == null) return;
        try {
            String myAccountId = accountService.currentAccountIdOrNull();
            if (myAccountId == null || myAccountId.isBlank()) return;
            JSONObject resp = JSONUtil.parseObj(httpGet(
                    conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId() + "/members",
                    conn.getDeviceToken()));
            if (resp.getInt("code", 1) != 0) return;
            JSONArray rows = resp.getJSONArray("data");
            cacheRemoteDisplayNames(remote.getProjectId(), rows);
            if (rows == null) return;
            for (Object o : rows) {
                if (!(o instanceof JSONObject row)) continue;
                if (!myAccountId.equals(row.getStr("accountId"))) continue;
                Long userId = row.getLong("userId", null);
                if (userId == null) continue;
                conn.setRemoteUserId(userId);
                connectionRepository.save(conn);
                log.info("已回填案件库侧的 userId: connection={} remoteUserId={}", conn.getId(), userId);
                return;
            }
            log.info("案件库参与人里没有与本机账户对得上的行，事件行仍按他人渲染: connection={}", conn.getId());
        } catch (Exception e) {
            log.warn("回填案件库侧 userId 失败（已吞）: connection={}", conn.getId(), e);
        }
    }

    /**
     * 上报「我取回了最新稿」。这件事只有客户端知道——服务端那一侧就是一次普通的
     * upload-pack，和日常轮询分不开。失败只记日志：少一行旁白而已，绝不能让一次
     * 已经落地的取回报错。
     */
    private void reportPulled(long projectId, CloudConnection conn) {
        try {
            ProjectRemote remote = remoteRepository.findByProjectId(projectId).orElse(null);
            if (remote == null || conn == null) return;
            Map<String, Object> body = new HashMap<>();
            body.put("kind", "PULLED");
            body.put("toSha", repoService.resolveRef(projectId, repoService.mainBranch()));
            httpPost(conn.getServerUrl() + "/api/projects/" + remote.getRemoteProjectId()
                    + "/collab-events", JSONUtil.toJsonStr(body), conn.getDeviceToken());
        } catch (Exception e) {
            log.warn("上报取回记录失败（已吞）: project={}", projectId, e);
        }
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
            throw VersionException.userFacing(LangText.of(
                    "没能把人加进来：" + resp.getStr("message", "请重试"),
                    "Couldn't add that member: " + resp.getStr("message", "please try again")));
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
    // （副本来自云端侧，applyResolution 的 draftName 传 cloudSideLabel()）。与 Task 7
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
            reportPulled(projectId, conn);
            return new UpdateResult(UpdateStatus.UPDATED, affectedSince(projectId, tipBefore), null);
        }
        // 真合并：两条已分叉的线
        MergeOutcome outcome = repoService.mergeNoCommit(projectId, ORIGIN_MASTER,
                cloudMergeTitle(), userName, authorEmail(projectId, userId, userName));
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
                // 自裁的清单冲突不是律师做的选择，不记裁决尾注。
                return completeCloudMerge(projectId, tipBefore, remoteSha, conn, userId, userName, Map.of());
            }
            return new UpdateResult(UpdateStatus.CONFLICT, List.of(), cloudConflictPayload(projectId, userId));
        }
        return completeCloudMerge(projectId, tipBefore, remoteSha, conn, userId, userName, Map.of());
    }

    /** 冲突裁决：逐文件三选一，choices 必须覆盖全部待选文件，随后与干净路径走同一条收尾。 */
    public UpdateResult resolveCloudMerge(long projectId,
                                          Map<String, WorkSessionService.Resolution> resolutions,
                                          Long userId, String userName) {
        ReentrantLock lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing(LangText.of("现在没有等你做选择的文件", "There are no files waiting on your choice right now"));
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
                if (choices.get(path) == null) throw VersionException.userFacing(LangText.of("还有文件没选留哪一份", "There are still files where you haven't picked which version to keep"));
            }
            requireMergedHasPendingRecord(projectId, choices, conflicts);
            for (String path : conflicts) {
                sessionService.applyResolution(projectId, path, choices.get(path),
                        mainTip, cloudTip, cloudSideLabel());
            }
            var remote = remoteRepository.findByProjectId(projectId).orElseThrow();
            return completeCloudMerge(projectId, mainTip, cloudTip,
                    connectionOf(remote), userId, userName,
                    WorkSessionService.resolutionNames(choices, conflicts));
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
                throw VersionException.userFacing(LangText.of("现在没有等你做选择的文件", "There are no files waiting on your choice right now"));
            }
            requireCloudMergeWindow(projectId, repoService.mergeHeadRef(projectId));
            repoService.abortMerge(projectId);
            // 待决记录与合并窗口同寿（同 WorkSessionService.abortAdopt）
            if (pendingMergeStore != null) pendingMergeStore.clear(projectId);
            return LangText.of("这次没有取回，你的内容分毫未动", "Nothing was pulled — your content is untouched");
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
     * 重推失败（网络问题）不回滚——合并已经落地，只是回传没成，转入待上传，绝不丢改动；
     * 落没落地随 {@link UpdateResult#landedOnCloud()} 回给调用方（上传路径据此不报成功交稿）。
     */
    private UpdateResult completeCloudMerge(long projectId, String tipBefore, String cloudTip,
                                            CloudConnection conn, Long userId, String userName,
                                            Map<String, String> resolutions) {
        var cloudManifest = manifestService.readAtRef(projectId, cloudTip);
        String baseSha = repoService.mergeBase(projectId, tipBefore, cloudTip);
        TreeManifest base = baseSha == null ? null : manifestService.readAtRef(projectId, baseSha);
        if (cloudManifest != null) manifestService.unionApply(projectId, cloudManifest, base);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        repoService.commitMergeResolution(projectId, cloudMergeTitle(), resolutions,
                pendingMergeStore == null ? List.of() : pendingMergeStore.all(projectId),
                ProjectRepoService.MERGE_CONTEXT_CLOUD,
                userName, authorEmail(projectId, userId, userName));
        // 提交成功之后才清：提交失败时记录还在，律师重试一次照样能收尾。
        if (pendingMergeStore != null) pendingMergeStore.clear(projectId);
        boolean landed = false;
        try {
            // 重推被拒是返回值不是异常（裁决窗口期间远端又被同事推进了一版）：不接住的话
            // 会绿灯假同步——pendingUpload=false + lastSyncSha=本地 sha，界面显示「已与云端
            // 同步」而云端根本没有这份裁决结果（v2 终审 I1）。
            ProjectRepoService.PushOutcome out = repoService.pushMainlineToOrigin(
                    projectId, conn.getUsername(), conn.getDeviceToken());
            landed = out.pushed();
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
        reportPulled(projectId, conn);
        return new UpdateResult(UpdateStatus.UPDATED, affectedSince(projectId, tipBefore), null, landed);
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
            throw VersionException.userFacing(LangText.of("正在处理的是另一件事，请先把它处理完", "Something else is already in progress — please finish that first"));
        }
        var active = sessionService.activeSession(projectId);
        if (active.isPresent() && mergeHead.equals(
                repoService.resolveRef(projectId, active.get().getBranchName()))) {
            throw VersionException.userFacing(LangText.of("正在处理的是另一件事，请先把它处理完", "Something else is already in progress — please finish that first"));
        }
        String originSha = repoService.originMasterSha(projectId);
        boolean cloudWindow = mergeHead.equals(originSha)
                || (originSha != null && repoService.isAncestor(projectId, mergeHead, ORIGIN_MASTER));
        if (!cloudWindow) {
            throw VersionException.userFacing(LangText.of("正在处理的是另一件事，请先把它处理完", "Something else is already in progress — please finish that first"));
        }
    }

    /** updateFromCloud 的前置守卫：没有进行中的合并/工作/稿，理由同采纳前置（避免几件事缠在一起）。 */
    private void requireCleanForCloudOps(long projectId) {
        if (repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing(LangText.of("请先把等你做选择的文件处理完", "Please finish choosing your files first"));
        }
        if (sessionService.activeSession(projectId).isPresent()) {
            throw VersionException.userFacing(LangText.of("请先结束或丢弃手头这段工作，再取回最新稿", "Please finish or discard your current work session before pulling the latest draft"));
        }
        if (sessionService.onDraftBranch(projectId)) {
            throw VersionException.userFacing(LangText.of("请先回到主线工作，再取回最新稿", "Please return to Mainline before pulling the latest draft"));
        }
    }

    /** 冲突窗口 payload，形状同 VersionController.cloudConflictStatus——两处独立反查，不共享代码是故意的（一个在写入时机知道，一个在 /status 轮询时反查）。 */
    private Map<String, Object> cloudConflictPayload(long projectId, Long userId) {
        Map<String, Object> m = new HashMap<>();
        m.put("conflictingPaths", WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId)));
        m.put("mainlineTip", repoService.resolveRef(projectId, "HEAD"));
        m.put("cloudTip", repoService.mergeHeadRef(projectId));
        // 三方合并那三个字段（spec 2026-09-14 §4.3）由分析服务统一拼——**不许在这里
        // 复制一份**：/status 的 cloudConflictStatus 与这里给的是同一个冲突窗口，
        // 两份独立实现走散之后前端会按同一套代码渲染出两种形状。
        m.putAll(mergeExtras(projectId, userId));
        return m;
    }

    /**
     * {@code MERGED} 的护栏，口径与 {@code WorkSessionService.requireMergedHasPendingRecord}
     * 逐字相同（那边是采纳/结束工作两个语境，这里是云端取回）：没有待决记录就说明
     * 工作区里躺着的还是带冲突标记的半成品，认下去等于把它提交进主线并推给同事。
     */
    private void requireMergedHasPendingRecord(long projectId,
                                               Map<String, WorkSessionService.Resolution> choices,
                                               List<String> conflicts) {
        for (String path : conflicts) {
            if (choices.get(path) != WorkSessionService.Resolution.MERGED) continue;
            if (pendingMergeStore == null || pendingMergeStore.get(projectId, path).isEmpty()) {
                throw VersionException.userFacing(LangText.of(
                        "这份文件还没有合并好的结果，请重新处理一遍",
                        "This file has no merged result yet — please work through it again"));
            }
        }
    }

    /**
     * 冲突窗口里那三个三方合并字段。分析服务缺席（手工 new 出本服务的单测）时回空表——
     * 冲突窗口退回 v2 的老形状，前端的 {@code documentMerges} 为空即全部整份三选一。
     */
    private Map<String, Object> mergeExtras(long projectId, Long userId) {
        if (mergeAnalysisService == null) return Map.of();
        try {
            return mergeAnalysisService.conflictExtras(projectId, userId,
                    remoteDisplayNames(projectId, false));
        } catch (Exception e) {
            log.warn("拼装三方合并字段失败，这次冲突窗口按整份三选一给: project={}", projectId, e);
            return Map.of();
        }
    }

    private CloudConnection connectionOf(ProjectRemote remote) {
        return connectionRepository.findById(remote.getConnectionId())
                .orElseThrow(() -> new VersionException("云端连接不存在: " + remote.getConnectionId()));
    }

    /**
     * 合并提交的作者邮箱，规则集中在 {@link VersionAuthorResolver}（spec 2026-09-14 §2.1）。
     * userId 为 null（uploadToCloud 的自动整合无用户上下文）时由 resolver 退化为账号名——
     * 不过这条路径上项目必然已经绑定案件库，拿到的是账户级的 collab 邮箱。
     */
    private String authorEmail(long projectId, Long userId, String userName) {
        return authorResolver != null
                ? authorResolver.email(projectId, userId, userName)
                : VersionAuthorResolver.localEmail(userName);
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
