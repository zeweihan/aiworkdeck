package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关闭版本记录并删除历史（dev-board#438）。默认自动开启之后，律师必须能拒绝它。
 */
class VersionDisableTest {

    private static final long PROJECT_ID = 7L;

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private WorkSessionRepository sessionRepo;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new ProjectStorageResolver(props, null));
        repoSvc.init(PROJECT_ID, "韩泽伟", "hzw@example.com");

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(PROJECT_ID)).thenReturn(new ArrayList<>());
        ProjectTreeManifestService manifestSvc = new ProjectTreeManifestService(
                fileRepo, repoSvc, new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));

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
        when(sessionRepo.findByProjectIdOrderByStartedAtDesc(any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0)))
                        .toList());
        org.mockito.Mockito.doAnswer(i -> {
            for (Object s : (Iterable<?>) i.getArgument(0)) sessions.remove(((WorkSession) s).getId());
            return null;
        }).when(sessionRepo).deleteAll(any(Iterable.class));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, e -> {});
        svc.setDebounceMillis(60_000);
    }

    @Test
    void disableDeletesHistoryAndSessionRowsButLeavesUserFilesUntouched() throws Exception {
        svc.onChangeSignal(PROJECT_ID, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(PROJECT_ID, 1L, "韩泽伟", "改了");
        assertTrue(repoSvc.isInitialized(PROJECT_ID));
        assertFalse(sessions.isEmpty(), "前提：有一段进行中的工作");

        svc.disableVersionRecording(PROJECT_ID);

        assertFalse(repoSvc.isInitialized(PROJECT_ID), "版本库目录应被整个删掉");
        assertFalse(Files.exists(repoSvc.gitDir(PROJECT_ID)), "gitDir 仍在磁盘上");
        assertTrue(sessions.isEmpty(), "work_session 行应一并删掉（含进行中的工作段与稿）");
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "关闭版本记录绝不许改动工作区里的用户文件——律师此刻看到的那一份就是他要留下的");
        assertFalse(Files.exists(root.resolve("projects/7/.awd")),
                ".awd/ 是我们自己写的清单，关闭时一并清掉");
    }

    @Test
    void disableCancelsPendingAutosaveAndIdleTimers() throws Exception {
        svc.onChangeSignal(PROJECT_ID, 1L, "韩泽伟");
        assertNotNull(mapField("pending").get(PROJECT_ID), "前提：防抖存档已排上");
        assertNotNull(mapField("idleTimers").get(PROJECT_ID), "前提：空闲定时器已武装");

        svc.disableVersionRecording(PROJECT_ID);

        assertFalse(mapField("pending").containsKey(PROJECT_ID),
                "关闭之后防抖存档还排着，到点会对着已经删掉的仓库跑");
        assertFalse(mapField("idleTimers").containsKey(PROJECT_ID), "空闲定时器应一并取消");
        assertFalse(mapField("actors").containsKey(PROJECT_ID), "待存档的操作者也该清掉");
    }

    @Test
    void disableIsRefusedWhileFilesAreWaitingOnAChoice() {
        ProjectRepoService merging = mock(ProjectRepoService.class);
        when(merging.isInitialized(PROJECT_ID)).thenReturn(true);
        when(merging.repositoryMerging(PROJECT_ID)).thenReturn(true);
        WorkSessionService s = new WorkSessionService(merging,
                mock(ProjectTreeManifestService.class), sessionRepo,
                new ThreadPoolTaskScheduler(), mock(ProjectFileRepository.class), e -> {});

        VersionException e = assertThrows(VersionException.class,
                () -> s.disableVersionRecording(PROJECT_ID));

        assertTrue(e.isUserFacing(), "这是写给律师看的业务提示");
        verify(merging, never()).deleteRepository(anyLong());
    }

    @Test
    void disableIsRefusedWhileTheCaseFileLivesInTheTeamLibrary() {
        ProjectRemoteRepository remotes = mock(ProjectRemoteRepository.class);
        when(remotes.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(new ProjectRemote()));
        ProjectRepository projects = mock(ProjectRepository.class);
        WorkSessionService sessionService = mock(WorkSessionService.class);
        VersionLifecycleService lifecycle = new VersionLifecycleService(
                sessionService, repoSvc, projects, mock(UserRepository.class), remotes, Runnable::run);

        VersionException e = assertThrows(VersionException.class,
                () -> lifecycle.disableVersionRecording(PROJECT_ID));

        assertTrue(e.isUserFacing());
        verify(sessionService, never()).disableVersionRecording(anyLong());
        verify(projects, never()).save(any());
    }

    @Test
    void optOutIsRolledBackWhenTheDisableItselfFails() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        WorkSessionService sessionService = mock(WorkSessionService.class);
        org.mockito.Mockito.doThrow(VersionException.userFacing("有文件正等你做选择，请先处理完再关闭"))
                .when(sessionService).disableVersionRecording(PROJECT_ID);
        VersionLifecycleService lifecycle = new VersionLifecycleService(
                sessionService, repoSvc, projects, mock(UserRepository.class),
                mock(ProjectRemoteRepository.class), Runnable::run);

        assertThrows(VersionException.class, () -> lifecycle.disableVersionRecording(PROJECT_ID));

        assertFalse(Boolean.TRUE.equals(project.getVersionOptOut()),
                "关闭没成功就不该留下「以后别再自动开」的标记——那会让版本记录既没关掉又不再自愈");
    }

    @Test
    void repoSizeIsReportedAndFallsBackToZeroAfterDisabling() {
        assertTrue(repoSvc.repoSizeBytes(PROJECT_ID) > 0, "已开启时应报得出占用");
        svc.disableVersionRecording(PROJECT_ID);
        assertEquals(0L, repoSvc.repoSizeBytes(PROJECT_ID), "仓库没了就是 0，不该抛");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ?> mapField(String name) throws Exception {
        Field f = WorkSessionService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Long, ?>) f.get(svc);
    }
}
