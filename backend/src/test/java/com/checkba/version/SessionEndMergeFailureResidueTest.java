package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * endSession 先 checkout 主线、再让合并异常直接逃逸出去，留下一个残局：
 * WorkSession 还是 ACTIVE，但 HEAD 已经在主线上。discardSession 早就为这个残局
 * 加过显式的切回分支护栏（见它自己的注释），但 ensureSession——onChangeSignal /
 * commitNow / revertTo 全部隐式开段的唯一收口——复用已存在的 ACTIVE 段时不做这个
 * 检查，于是残局之后的每一次自动存档都直接落在主线上，绕开整个工作段隔离模型。
 *
 * fixture 照 SessionEndConflictTest（真实 Git 仓库 + HashMap 假仓储），另外把
 * ProjectRepoService 包一层 spy，用来把合并期的 I/O 故障（mergeCore 会把任何
 * IOException/JGit 异常包成 VersionException 抛出）注进去。
 */
class SessionEndMergeFailureResidueTest {

    private Path root;
    private ProjectRepoService repo;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;
    private Map<Long, ProjectFile> db;
    private long nextFileId;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService real = new ProjectRepoService(
                new com.checkba.storage.ProjectStorageResolver(props, null));
        real.init(7L, "韩泽伟", "hzw@example.com");
        repo = spy(real);

        db = new HashMap<>();
        nextFileId = 100L;
        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(any())).thenAnswer(i -> {
            Long pid = i.getArgument(0);
            List<ProjectFile> out = new ArrayList<>();
            for (ProjectFile f : db.values()) if (f.getProjectId().equals(pid)) out.add(f);
            return out;
        });
        when(fileRepo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextFileId++);
            db.put(p.getId(), p);
            return p;
        });
        ProjectTreeManifestService manifestSvc = new ProjectTreeManifestService(
                fileRepo, repo, new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));

        sessions = new HashMap<>();
        nextSessionId = 1L;
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
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
        when(sessionRepo.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(any(), any(), any()))
                .thenAnswer(i -> sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1)
                                && s.getSessionType() == i.getArgument(2))
                        .sorted(Comparator.comparing(WorkSession::getStartedAt).reversed())
                        .toList());
        when(sessionRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.getArgument(0))));

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repo, manifestSvc, sessionRepo, scheduler, fileRepo, event -> {});
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动落版
    }

    private void write(String relPath, String content) throws Exception {
        Files.writeString(root.resolve("projects/7").resolve(relPath), content);
    }

    private WorkSession activeSession() {
        return sessions.values().stream()
                .filter(s -> s.getStatus() == WorkSession.Status.ACTIVE)
                .findFirst().orElseThrow();
    }

    @Test
    void autosaveAfterFailedEndSessionMustNotLandOnMainline() throws Exception {
        svc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        write("合同.txt", "工作段里的修改");
        svc.commitNow(7L, 1L, "韩泽伟", null);

        WorkSession active = activeSession();
        String sessionBranch = active.getBranchName();
        String mainTipBefore = repo.resolveRef(7L, repo.mainBranch());

        // 合并期真实故障：mergeCore 把任何 IO/JGit 异常包成 VersionException 抛出。
        doThrow(new VersionException("模拟合并期 I/O 故障"))
                .when(repo).merge(anyLong(), anyString(), anyString(), anyString(), anyString());

        assertThrows(VersionException.class,
                () -> svc.endSession(7L, 1L, "韩泽伟", "这段工作"));

        // 段没结成，仍然 ACTIVE——这条本来就该如此，不是病灶。
        assertEquals(WorkSession.Status.ACTIVE, sessions.get(active.getId()).getStatus(),
                "前提不成立：合并没成，这段工作就该还挂着");

        // 病灶正身：合并「返回值失败」两条路径都会切回工作分支，唯独「抛异常」那条
        // 让异常直接逃逸，把 HEAD 丢在主线上，段与 HEAD 就此脱节。
        assertEquals(sessionBranch, repo.currentBranch(7L),
                "合并抛异常后 HEAD 被丢在主线上：工作段与 HEAD 脱节");

        // 用户可见后果：脱节之后律师继续敲字，自动存档会顺着当前 HEAD 直接落进主线，
        // 绕开整个工作段隔离（历史永不重写，落进去就永久污染）。
        write("合同.txt", "残局之后又敲了一段");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.commitNow(7L, 1L, "韩泽伟", null);

        assertEquals(mainTipBefore, repo.resolveRef(7L, repo.mainBranch()),
                "残局之后的自动存档直接落在主线上：工作段隔离被绕开了");
        assertEquals(sessionBranch, repo.currentBranch(7L),
                "自动存档没有把 HEAD 切回这段工作自己的分支");
    }
}
