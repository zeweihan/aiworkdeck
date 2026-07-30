package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 第 3 期 Task 1：稿分支（draft/*）上的工作段守卫。
 * 稿是长命分支，绝不自动合并、不受 30 分钟空闲结束管辖，改动信号也不得隐式开工作段。
 */
class DraftSessionGuardTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private ProjectTreeManifestService manifestSvc;
    private WorkSessionRepository sessionRepo;
    private ThreadPoolTaskScheduler scheduler;
    private ProjectFileRepository fileRepo;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");

        fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(7L)).thenReturn(new ArrayList<>());
        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper());

        sessions = new HashMap<>();
        nextSessionId = 1L;
        sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextSessionId++);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findFirstByProjectIdAndStatusAndSessionType(any(), any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1)
                                && s.getSessionType() == i.getArgument(2))
                        .findFirst());

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo);
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动 commitNow
    }

    @Test
    void changeSignalOnDraftBranchDoesNotCreateWorkSession() throws Exception {
        // 手工造一条稿分支并切过去（Task 3 才有 createDraft，这里直接用 repo 层）
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");

        svc.onChangeSignal(7L, 1L, "韩泽伟");

        assertTrue(svc.activeSession(7L).isEmpty(), "稿上改动不得隐式开工作段");
        assertEquals("draft/1001", repoSvc.currentBranch(7L), "不得被切走");
    }

    @Test
    void draftBranchChangeStillAutoArchives() throws Exception {
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿上改动");

        String sha = svc.commitNow(7L, 1L, "韩泽伟", null);

        assertNotNull(sha, "稿上防抖存档必须照常工作");
        assertEquals("auto", repoSvc.log(7L, "HEAD", 1).get(0).kind());
    }

    @Test
    void idleTimerNotArmedOnDraft() throws Exception {
        repoSvc.createBranch(7L, "draft/1001", "HEAD");
        repoSvc.checkoutBranch(7L, "draft/1001");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        // 反射读 idleTimers（模式照 WorkSessionServiceTest 既有写法）
        var f = WorkSessionService.class.getDeclaredField("idleTimers");
        f.setAccessible(true);
        var timers = (java.util.Map<?, ?>) f.get(svc);
        assertFalse(timers.containsKey(7L), "稿上不得武装空闲自动结束");
    }
}
