// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.service.telemetry.TelemetryService;
import com.checkba.storage.StorageProperties;
import com.checkba.version.ProjectRepoService;
import com.checkba.version.ProjectTreeManifestService;
import com.checkba.version.VersionController;
import com.checkba.version.VersionLifecycleService;
import com.checkba.version.WorkSession;
import com.checkba.version.WorkSessionRepository;
import com.checkba.version.WorkSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.MockedStatic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 三方合并端点用的现场：一个真的 Git 仓库 + HashMap 背后的假仓储 + 一个手工装配的
 * {@link VersionController}。骨架照 {@code DraftAdoptTest}／{@code CloudSyncUpdateTest}——
 * 断言一律落在真实的文件字节、真实的提交尾注与真实的待决记录上，不 mock 被测对象。
 *
 * <p>多个用例共用，所以抽成这一个类；它本身不含断言。
 */
final class MergeScene implements AutoCloseable {

    static final long PROJECT_ID = 7L;
    static final long USER_ID = 1L;
    static final String SESSION = "sess";

    final Path root;
    final ProjectRepoService repoSvc;
    final ProjectTreeManifestService manifestSvc;
    final WorkSessionService sessionSvc;
    final PendingMergeStore pendingStore;
    final MergeAnalysisService analysisSvc;
    final VersionController controller;
    final ProjectMemberService memberService;

    final Map<Long, WorkSession> sessions = new HashMap<>();
    final Map<Long, ProjectFile> db = new HashMap<>();

    private long nextSessionId = 1L;
    private long nextFileId = 100L;
    private final ThreadPoolTaskScheduler scheduler;
    private final MockedStatic<AuthController> auth;

    MergeScene(Path tmp) throws Exception {
        this.root = tmp;
        Files.createDirectories(root.resolve("projects/" + PROJECT_ID));

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        repoSvc.init(PROJECT_ID, "韩泽伟", "hzw@example.com");

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
        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper(),
                mock(UserRepository.class), mock(ProjectRepository.class));

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

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        sessionSvc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo, e -> {});
        sessionSvc.setDebounceMillis(60_000);

        pendingStore = new PendingMergeStore(repoSvc);
        analysisSvc = new MergeAnalysisService(repoSvc, pendingStore);
        sessionSvc.setPendingMergeStoreForTest(pendingStore);

        memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(any(), any())).thenReturn(true);
        when(memberService.hasWritePermission(any(), any())).thenReturn(true);
        when(memberService.isClient(any(), any())).thenReturn(false);

        TelemetryService telemetry = mock(TelemetryService.class);
        controller = new VersionController(repoSvc, sessionSvc, memberService,
                mock(UserService.class), mock(ProjectFileService.class), telemetry,
                mock(VersionLifecycleService.class));
        controller.setMergeAnalysisServiceForTest(analysisSvc);
        controller.setPendingMergeStoreForTest(pendingStore);

        auth = mockStatic(AuthController.class);
        auth.when(() -> AuthController.getUserIdFromSession(SESSION)).thenReturn(USER_ID);
    }

    @Override
    public void close() {
        auth.close();
        scheduler.shutdown();
    }

    // ---- 工作区 ------------------------------------------------------------

    void write(String rel, byte[] bytes) throws Exception {
        Path target = root.resolve("projects/" + PROJECT_ID).resolve(rel);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    byte[] read(String rel) throws Exception {
        return Files.readAllBytes(root.resolve("projects/" + PROJECT_ID).resolve(rel));
    }

    /** 主线上完整走一段工作：隐式开段 → 收尾提交 → 合并回主线。 */
    void mainlineWork(String title) {
        sessionSvc.onChangeSignal(PROJECT_ID, USER_ID, "韩泽伟");
        sessionSvc.endSession(PROJECT_ID, USER_ID, "韩泽伟", title);
    }

    // ---- 夹具文档 ----------------------------------------------------------

    static final List<String> BASE_PARAGRAPHS = List.of(
            "第一条 甲方", "第二条 乙方", "第三条 标的", "第四条 价款", "第五条 交付");

    /** 基线 docx 的第 {@code index} 段换成 {@code text}。 */
    static byte[] docxWith(int index, String text) {
        List<String> list = new ArrayList<>(BASE_PARAGRAPHS);
        list.set(index, text);
        return MergeFixtures.docx(list.toArray(new String[0]));
    }

    static byte[] baseDocx() {
        return MergeFixtures.docx(BASE_PARAGRAPHS.toArray(new String[0]));
    }

    // ---- 三个语境的冲突现场 -------------------------------------------------

    /**
     * 采纳撞车：主线改了第 2 段、稿改了第 5 段，两边都动过同一份 docx
     * （字节级冲突——docx 是二进制，JGit 一律整份判 unmerged，见 version-control.md）。
     *
     * @return 稿 id
     */
    long stageAdoptConflict(String path) throws Exception {
        return stageAdoptConflictOn(path, baseDocx(),
                docxWith(1, "第二条 乙方（我改的）"), docxWith(4, "第五条 交付（律师乙改的）"));
    }

    /** 同上，但三份字节由调用方给（pdf 等非文档类型用）。 */
    long stageAdoptConflictOn(String path, byte[] base, byte[] mainSide, byte[] otherSide) throws Exception {
        write(path, base);
        mainlineWork("起点");

        WorkSessionService.DraftCreateResult created =
                sessionSvc.createDraft(PROJECT_ID, null, "试验稿", USER_ID, "韩泽伟");
        write(path, otherSide);
        sessionSvc.commitNow(PROJECT_ID, USER_ID, "韩泽伟", "稿上存档");

        sessionSvc.switchToMainline(PROJECT_ID, USER_ID, "韩泽伟");
        write(path, mainSide);
        mainlineWork("主线的工作");

        sessionSvc.switchToDraft(PROJECT_ID, created.draft().getId(), USER_ID, "韩泽伟");
        WorkSessionService.AdoptOutcome r =
                sessionSvc.adoptDraft(PROJECT_ID, created.draft().getId(), USER_ID, "韩泽伟");
        if (r.success() || !repoSvc.repositoryMerging(PROJECT_ID)) {
            throw new AssertionError("前置：这一步应该造出一个采纳冲突");
        }
        return created.draft().getId();
    }

    /**
     * 结束工作撞车：这段工作在自己的分支上改了第 5 段，期间主线被推进（改第 2 段），
     * 收尾时撞上。
     *
     * @return 工作段 id
     */
    long stageSessionEndConflict(String path) throws Exception {
        write(path, baseDocx());
        mainlineWork("起点");

        // 手头这段工作：开段 → 落一笔存档（此时 HEAD 在工作段分支上）
        sessionSvc.onChangeSignal(PROJECT_ID, USER_ID, "韩泽伟");
        write(path, docxWith(4, "第五条 交付（我改的）"));
        sessionSvc.commitNow(PROJECT_ID, USER_ID, "韩泽伟", "存档");
        long sessionId = sessionSvc.activeSession(PROJECT_ID).orElseThrow().getId();

        // 同事推进主线：直接在主线分支上落一版（模拟取回后的主线前移）
        String sessionBranch = sessions.get(sessionId).getBranchName();
        repoSvc.checkoutBranch(PROJECT_ID, repoSvc.mainBranch());
        write(path, docxWith(1, "第二条 乙方（同事改的）"));
        repoSvc.commitAll(PROJECT_ID, "同事的改动", "session", null, "李律师", "li@example.com");
        repoSvc.checkoutBranch(PROJECT_ID, sessionBranch);

        WorkSessionService.SessionEndResult r =
                sessionSvc.endSession(PROJECT_ID, USER_ID, "韩泽伟", "我的一段工作");
        if (r.conflict() == null || !repoSvc.repositoryMerging(PROJECT_ID)) {
            throw new AssertionError("前置：这一步应该造出一个结束工作撞车");
        }
        return sessionId;
    }
}
