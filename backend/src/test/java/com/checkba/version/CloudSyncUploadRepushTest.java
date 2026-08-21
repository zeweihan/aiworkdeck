package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 上传被拒 → 自动整合 → 合并后重推的诚实回报（稳定性审计）。
 *
 * 全 mock 的窄口径用例：真实双仓 fixture（CloudSyncUpdateTest）没法在 integrateFromCloud
 * 的 fetch 与 completeCloudMerge 的重推之间插一手让远端再前进一版，而这正是本用例要
 * 复现的时序——合并在本地落地了，回传却没到云端。
 */
class CloudSyncUploadRepushTest {

    private static final long PROJECT = 7L;
    private static final String LOCAL_TIP = "local-tip-sha";
    private static final String CLOUD_TIP = "cloud-tip-sha";

    private ProjectRepoService repo;
    private WorkSessionService sessions;
    private ProjectRemoteRepository remotes;
    private ProjectRemote remoteRow;
    private CloudSyncService cloud;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectRepoService.class);
        sessions = mock(WorkSessionService.class);
        ProjectTreeManifestService manifests = mock(ProjectTreeManifestService.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        CloudConnectionRepository connections = mock(CloudConnectionRepository.class);
        remotes = mock(ProjectRemoteRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);

        CloudConnection conn = new CloudConnection();
        conn.setId(1L);
        conn.setUsername("韩泽伟");
        conn.setDisplayName("韩泽伟");
        conn.setDeviceToken("awdt_test");
        when(connections.findById(1L)).thenReturn(Optional.of(conn));

        remoteRow = new ProjectRemote();
        remoteRow.setId(1L);
        remoteRow.setProjectId(PROJECT);
        remoteRow.setConnectionId(1L);
        remoteRow.setPendingUpload(false);
        remoteRow.setLastSyncSha("已同步到这里");
        when(remotes.findByProjectId(PROJECT)).thenReturn(Optional.of(remoteRow));
        when(remotes.save(any(ProjectRemote.class))).thenAnswer(i -> i.getArgument(0));

        when(sessions.repoLock(PROJECT)).thenReturn(new ReentrantLock());
        when(sessions.activeSession(PROJECT)).thenReturn(Optional.empty());
        when(sessions.onDraftBranch(PROJECT)).thenReturn(false);
        when(sessions.resolveAffectedFileIds(anyLong(), any())).thenReturn(List.of(42L));

        when(repo.mainBranch()).thenReturn("master");
        when(repo.repositoryMerging(PROJECT)).thenReturn(false);
        when(repo.fetchFromOrigin(anyLong(), any(), any())).thenReturn(CLOUD_TIP);
        when(repo.resolveRef(anyLong(), any())).thenReturn(LOCAL_TIP);
        // 两条线已分叉：云端不是本地的祖先，本地也不是云端的祖先 → 走真合并
        when(repo.isAncestor(anyLong(), any(), any())).thenReturn(false);
        when(repo.mergeNoCommit(anyLong(), any(), any(), any(), any()))
                .thenReturn(new MergeOutcome(true, false, List.of(), null));
        when(repo.diffNameStatus(anyLong(), any(), any())).thenReturn(List.of());

        cloud = new CloudSyncService(repo, sessions, manifests, files, connections, remotes, projects);
    }

    /** 第一次推被拒（同事先交了稿），第二次推（合并后的回传）按 outcome 给结果。 */
    private void pushRejectedThen(ProjectRepoService.PushOutcome second) {
        when(repo.pushMainlineToOrigin(anyLong(), any(), any()))
                .thenReturn(new ProjectRepoService.PushOutcome(false, true, "远端已前移"))
                .thenReturn(second);
    }

    /**
     * 合并落地但回传没到云端（裁决窗口那一瞬远端又被推进 / 网络抖断）：绝不能报 UPLOADED
     * ——界面会显示「已交稿」，而云端根本没有这份合并，同事永远看不到。
     */
    @Test
    void repushRejectedAfterAutoMergeIsNotReportedAsUploaded() {
        pushRejectedThen(new ProjectRepoService.PushOutcome(false, true, "远端又被推进了"));

        CloudSyncService.UploadResult r = cloud.uploadToCloud(PROJECT, false);

        assertNotEquals(CloudSyncService.UploadStatus.UPLOADED, r.status(),
                "合并没到云端，不能报成功交稿");
        assertEquals(CloudSyncService.UploadStatus.OFFLINE_PENDING, r.status());
        assertNotNull(r.message(), "要给律师一句实话：这次没交上，改动已记下");
        assertTrue(remoteRow.getPendingUpload(), "待上传黄灯要留着");
        assertEquals(List.of(42L), r.affectedFileIds(),
                "整合改写了磁盘，受影响文件 id 仍要带回给重载链");
    }

    /** 重推抛异常（连接断了）与被拒同口径。 */
    @Test
    void repushThrowingAfterAutoMergeIsNotReportedAsUploaded() {
        when(repo.pushMainlineToOrigin(anyLong(), any(), any()))
                .thenReturn(new ProjectRepoService.PushOutcome(false, true, "远端已前移"))
                .thenThrow(new VersionException("连接断了"));

        CloudSyncService.UploadResult r = cloud.uploadToCloud(PROJECT, false);

        assertEquals(CloudSyncService.UploadStatus.OFFLINE_PENDING, r.status());
        assertTrue(remoteRow.getPendingUpload());
    }

    /** 反向守卫：重推真的落地了就该照常报 UPLOADED（别把好路径一起改红）。 */
    @Test
    void repushLandedAfterAutoMergeStaysUploaded() {
        pushRejectedThen(new ProjectRepoService.PushOutcome(true, false, null));

        CloudSyncService.UploadResult r = cloud.uploadToCloud(PROJECT, false);

        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status());
        assertFalse(remoteRow.getPendingUpload());
        assertEquals(List.of(42L), r.affectedFileIds());
    }

    /**
     * 反向守卫二：快进路径压根不推（本地没有云端没有的提交），整合完本地就等于云端
     * ——不能因为 remote.pendingUpload 的历史值把它误报成失败。
     */
    @Test
    void fastForwardIntegrateStaysUploadedEvenWithoutPush() {
        remoteRow.setPendingUpload(true); // 历史遗留的黄灯
        pushRejectedThen(new ProjectRepoService.PushOutcome(false, true, "远端已前移"));
        // 本地是云端的祖先 → 快进分支（不经过 completeCloudMerge，也就没有重推）
        when(repo.isAncestor(PROJECT, "master", "refs/remotes/origin/master")).thenReturn(true);

        CloudSyncService.UploadResult r = cloud.uploadToCloud(PROJECT, false);

        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status());
    }
}
