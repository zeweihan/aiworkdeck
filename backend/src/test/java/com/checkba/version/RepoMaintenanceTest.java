package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoMaintenanceTest {

    @Test
    void gcPreservesEveryReachableVersion(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        s.init(7L, "韩泽伟", "hzw@example.com");

        for (int i = 1; i <= 5; i++) {
            Files.writeString(root.resolve("projects/7/合同.txt"), "第 " + i + " 稿");
            s.commitAll(7L, "改了 " + i, "auto", null, "韩泽伟", "hzw@example.com");
        }

        List<VersionEntry> before = s.log(7L, "HEAD", 100);
        assertEquals(6, before.size());

        s.gc(7L);

        List<VersionEntry> after = s.log(7L, "HEAD", 100);
        assertEquals(before.size(), after.size(), "GC 不得删除任何可达版本");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).sha(), after.get(i).sha(),
                    "GC 前后的版本序列必须逐条相同——历史永不重写");
        }
        // 历史内容仍可取回
        assertEquals("初稿", new String(
                s.readBlobAtCommit(7L, before.get(before.size() - 1).sha(), "合同.txt")));
    }

    /**
     * 每日维护回收存量的已合并工作分支（dev-board#443）：三条判据同时成立才删。
     *
     * A：work/a 真的 NO_FF 合并进了主线 + 库里 MERGED  → 删
     * B：work/b tip 已是主线祖先，但库里还 ACTIVE       → 不删（崩溃后没收尾的工作段，
     *                                                    这条分支是律师改动的唯一容器）
     * C：work/c tip 已是主线祖先，但库里查不到对应行     → 不删（宁可留着）
     * D：draft/d tip 已是主线祖先 + 库里 MERGED          → 不删（前缀不对，稿不回收）
     * E：work/e 库里 MERGED，但 tip 还不是主线祖先       → 不删（状态位与仓库现实对不上，
     *                                                    删了就是真的删历史）
     *
     * 顺带钉死「删引用不删历史」：A 那笔提交在回收 + GC 之后仍从主线可达。
     */
    @Test
    void dailyMaintenanceReclaimsOnlyMergedWorkBranches(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService repo = new ProjectRepoService(
                new com.checkba.storage.ProjectStorageResolver(props, null));
        repo.init(7L, "韩泽伟", "hzw@example.com");

        // A：在自己的分支上改了东西，再 NO_FF 合并回主线（结束一段工作的真实形状）
        repo.createBranch(7L, "work/a", "HEAD");
        repo.checkoutBranch(7L, "work/a");
        Files.writeString(root.resolve("projects/7/合同.txt"), "A 的改动");
        repo.commitAll(7L, "A 的改动", "auto", null, "韩泽伟", "hzw@example.com");
        String aTip = repo.resolveRef(7L, "work/a");
        repo.checkoutBranch(7L, "master");
        assertTrue(repo.merge(7L, "work/a", "第一段工作", "韩泽伟", "hzw@example.com").success());

        // B/C/D：从主线当前 tip 开出来、一笔未改，tip 天然已是主线的祖先
        repo.createBranch(7L, "work/b", "HEAD");
        repo.createBranch(7L, "work/c", "HEAD");
        repo.createBranch(7L, "draft/d", "HEAD");
        for (String b : List.of("work/a", "work/b", "work/c", "draft/d")) {
            assertTrue(repo.isAncestor(7L, b, "master"), b + " 的 tip 应已是主线祖先");
        }

        // E：自己有一笔从没并进主线的提交，库里的状态位却写着 MERGED
        repo.createBranch(7L, "work/e", "HEAD");
        repo.checkoutBranch(7L, "work/e");
        Files.writeString(root.resolve("projects/7/未合并.txt"), "E 的改动");
        repo.commitAll(7L, "E 的改动", "auto", null, "韩泽伟", "hzw@example.com");
        String eTip = repo.resolveRef(7L, "work/e");
        repo.checkoutBranch(7L, "master");
        assertFalse(repo.isAncestor(7L, "work/e", "master"), "work/e 不该是主线祖先");

        WorkSessionRepository sessions = mock(WorkSessionRepository.class);
        when(sessions.findByProjectIdOrderByStartedAtDesc(7L)).thenReturn(List.of(
                session(1L, "work/a", WorkSession.Status.MERGED, WorkSession.SessionType.WORK),
                session(2L, "work/b", WorkSession.Status.ACTIVE, WorkSession.SessionType.WORK),
                // work/c 故意没有对应行
                session(3L, "draft/d", WorkSession.Status.MERGED, WorkSession.SessionType.DRAFT),
                session(4L, "work/e", WorkSession.Status.MERGED, WorkSession.SessionType.WORK)));

        Project p = new Project();
        p.setId(7L);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findAll()).thenReturn(List.of(p));

        new RepoMaintenanceJob(projects, repo, workSessionService(repo, sessions)).runDaily();

        List<String> left = repo.listBranches(7L);
        assertFalse(left.contains("work/a"),
                "已合并且库里 MERGED 的工作分支应被回收");
        // assertAll：一次报全部被误删的分支，而不是撞上第一条就停——
        // 去掉某一条判据时要能一眼看到它到底伤了谁。
        assertAll(
                () -> assertTrue(left.contains("work/b"),
                        "库里还 ACTIVE 的工作分支不许删——那是崩溃后没收尾的改动的唯一容器"),
                () -> assertTrue(left.contains("work/c"),
                        "库里查不到对应行的分支不许删——宁可留着"),
                () -> assertTrue(left.contains("draft/d"),
                        "稿分支不许自动回收——律师留着以后再决定"),
                () -> assertTrue(left.contains("work/e"),
                        "库里说 MERGED、但 tip 还不是主线祖先时不许删——删了就是真的删历史"),
                () -> assertTrue(left.contains("master"), "主线永远不许删"));
        assertNotNull(repo.readBlobAtCommit(7L, eTip, "未合并.txt"),
                "work/e 那笔从未并进主线的提交必须还在");

        // 删的是引用不是历史：A 的那笔提交在回收 + GC 之后仍从主线可达
        assertTrue(repo.log(7L, "master", 100).stream().anyMatch(v -> v.sha().equals(aTip)),
                "回收分支引用不得动历史——A 的提交仍须从主线可达");
        assertEquals("A 的改动", new String(repo.readBlobAtCommit(7L, aTip, "合同.txt")));
    }

    private static WorkSession session(long id, String branch,
                                       WorkSession.Status status, WorkSession.SessionType type) {
        WorkSession s = new WorkSession();
        s.setId(id);
        s.setProjectId(7L);
        s.setBranchName(branch);
        s.setStatus(status);
        s.setSessionType(type);
        s.setStartedAt(LocalDateTime.now());
        s.setUserId(1L);
        return s;
    }

    private static WorkSessionService workSessionService(ProjectRepoService repo,
                                                         WorkSessionRepository sessions) {
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        when(files.findByProjectId(any())).thenReturn(new ArrayList<>());
        ProjectTreeManifestService manifest = new ProjectTreeManifestService(
                files, repo, new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        return new WorkSessionService(repo, manifest, sessions, scheduler, files, event -> {});
    }
}
