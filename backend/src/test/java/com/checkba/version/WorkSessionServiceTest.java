package com.checkba.version;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkSessionServiceTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(7L)).thenReturn(new ArrayList<>());
        ProjectTreeManifestService manifestSvc =
                new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper());

        sessions = new HashMap<>();
        nextSessionId = 1L;
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextSessionId++);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findFirstByProjectIdAndStatus(any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1))
                        .findFirst());

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler);
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动 commitNow
    }

    @Test
    void firstChangeSignalStartsSessionImplicitly() {
        assertTrue(svc.activeSession(7L).isEmpty());

        svc.onChangeSignal(7L, 1L, "韩泽伟");

        var s = svc.activeSession(7L);
        assertTrue(s.isPresent());
        assertTrue(s.get().getBranchName().startsWith("work/"));
        assertEquals(s.get().getBranchName(), repoSvc.currentBranch(7L),
                "开始工作后应已切到工作分支");
    }

    @Test
    void secondSignalReusesTheSameSession() {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        String branch = svc.activeSession(7L).orElseThrow().getBranchName();
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertEquals(branch, svc.activeSession(7L).orElseThrow().getBranchName());
    }

    @Test
    void endSessionMergesBackToMainAndClosesSession() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        String sha = svc.endSession(7L, 1L, "韩泽伟", "发客户第一稿");

        assertNotNull(sha);
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.MERGED,
                sessions.values().iterator().next().getStatus());
    }

    @Test
    void endSessionGeneratesTitleWhenNotProvided() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.endSession(7L, 1L, "韩泽伟", null);

        String title = sessions.values().iterator().next().getTitle();
        assertNotNull(title);
        assertFalse(title.isBlank(), "未命名时服务端必须生成一个标题");
    }

    @Test
    void discardSessionThrowsAwayTheWholeBranch() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "不要的改动");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.discardSession(7L, 1L);

        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "丢弃后应回到主线内容");
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.DISCARDED,
                sessions.values().iterator().next().getStatus());
    }

    @Test
    void revertCreatesNewVersionRatherThanRewritingHistory() throws Exception {
        String firstSha = repoSvc.log(7L, "HEAD", 1).get(0).sha();

        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");
        svc.endSession(7L, 1L, "韩泽伟", "第一次工作");

        int before = repoSvc.log(7L, "HEAD", 100).size();
        String revertSha = svc.revertTo(7L, firstSha, 1L, "韩泽伟");
        int after = repoSvc.log(7L, "HEAD", 100).size();

        assertNotNull(revertSha);
        assertTrue(after > before, "退回必须新增版本，不得删除历史");
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
}
