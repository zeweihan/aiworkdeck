// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LocalProjectService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * AI 的「移入回收站」原语（dev-board#1044 D25），H2 真库 + 真 ProjectFileService + 本机文件夹项目。
 *
 * <p>病灶：AI 没有任何删除途径（delete_file 永久停用），被要求清掉中间产物时只能建一个
 * 「待删除」文件夹把东西挪进去。move_to_trash 必须与资源管理器右键「删除」走同一条软删路径：
 * 行标 isDeleted、进回收站列表、磁盘字节不动、对账不复活、能还原。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:move-to-trash-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FileToolsMoveToTrashTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectFileRepository projectFileRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private ProjectFileService projectFileService;
    private LocalProjectService localProjects;
    private EditorBridgeService bridge;
    private FileTools tools;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(tmp.resolve("appdata").toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, projectRepository);

        com.checkba.storage.StorageServiceFactory storageFactory = mock(com.checkba.storage.StorageServiceFactory.class);
        when(storageFactory.getStorageService()).thenReturn(mock(com.checkba.storage.StorageService.class));
        projectFileService = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                storageFactory,
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(projectFileService, "setStorageResolverForTest", resolver);

        ProjectMemberService memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);
        localProjects = new LocalProjectService(projectRepository, projectMemberRepository,
                projectFileRepository, projectFileService, memberService, resolver,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                transactionManager);

        bridge = mock(EditorBridgeService.class);
        tools = new FileTools(projectFileService, projectFileRepository, bridge, null, null, null, null, null, null);
    }

    @AfterEach
    void clear() {
        ProjectContextHolder.clear();
    }

    private Long openProject(Path folder) {
        Long pid = localProjects.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        ProjectContextHolder.setProjectId(String.valueOf(pid));
        ProjectContextHolder.setUserId(1L);
        return pid;
    }

    private ProjectFile row(Long pid, String name) {
        return projectFileRepository.findByProjectId(pid).stream()
                .filter(f -> name.equals(f.getName())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("按路径移入：行软删、回收站可见、磁盘字节不动、回执带恢复指引")
    void trashByPathIsSoftDeleteVisibleInRecycleBin(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("临时摘录.txt"), "scratch");
        Files.writeString(folder.resolve("合同.docx"), "keep");
        Long pid = openProject(folder);

        String out = tools.move_to_trash("[\"临时摘录.txt\"]");

        assertTrue(out.startsWith("trashed: 1; failed: 0"), out);
        assertTrue(out.contains(FileTools.TRASH_RECOVERY_HINT), "回执要告诉模型可从回收站恢复：" + out);
        assertTrue(Boolean.TRUE.equals(row(pid, "临时摘录.txt").getIsDeleted()));
        assertFalse(Boolean.TRUE.equals(row(pid, "合同.docx").getIsDeleted()), "没点名的文件不许动");
        List<ProjectFile> bin = projectFileService.getRecycleBinFiles(pid);
        assertEquals(List.of("临时摘录.txt"), bin.stream().map(ProjectFile::getName).toList());
        assertTrue(Files.exists(folder.resolve("临时摘录.txt")), "软删不碰磁盘：与用户手动删除同一语义");
    }

    @Test
    @DisplayName("按 fileId 移入文件夹：整棵子树进回收站，对账不复活，还原后全部回来")
    void trashFolderByIdCascadesAndRestores(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("草稿/子目录"));
        Files.writeString(folder.resolve("草稿/a.txt"), "a");
        Files.writeString(folder.resolve("草稿/子目录/b.txt"), "b");
        Long pid = openProject(folder);
        Long draftId = row(pid, "草稿").getId();

        String out = tools.move_to_trash("[" + draftId + "]");

        assertTrue(out.startsWith("trashed: 1; failed: 0"), out);
        for (String name : List.of("草稿", "子目录", "a.txt", "b.txt")) {
            assertTrue(Boolean.TRUE.equals(row(pid, name).getIsDeleted()), name + " 应随文件夹进回收站");
        }
        localProjects.reconcileProject(pid);
        assertEquals(4, projectFileRepository.findByProjectId(pid).size(), "对账不得把回收站里的行复活成新行");
        assertTrue(Files.exists(folder.resolve("草稿/子目录/b.txt")));

        projectFileService.restore(draftId, 1L);
        for (String name : List.of("草稿", "子目录", "a.txt", "b.txt")) {
            assertFalse(Boolean.TRUE.equals(row(pid, name).getIsDeleted()), name + " 应已还原");
        }
    }

    @Test
    @DisplayName("父文件夹先进回收站后，同批里它的子文件算已达成而不是失败")
    void childAfterParentInSameBatchIsNotAFailure(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("草稿"));
        Files.writeString(folder.resolve("草稿/a.txt"), "a");
        Long pid = openProject(folder);
        Long childId = row(pid, "a.txt").getId();

        String out = tools.move_to_trash("[\"草稿\", " + childId + "]");

        assertTrue(out.startsWith("trashed: 2; failed: 0"), out);
        assertTrue(out.contains("already in the recycle bin"), out);
    }

    @Test
    @DisplayName("别的项目的 fileId 拒绝且不动它；查无此路径单条失败不掀翻整批")
    void foreignIdAndUnknownPathFailPerItem(@TempDir Path base) throws Exception {
        Path other = Files.createDirectories(base.resolve("other"));
        Path folder = Files.createDirectories(base.resolve("mine"));
        Files.writeString(other.resolve("别人的.txt"), "x");
        Long otherPid = openProject(other);
        Long foreignId = row(otherPid, "别人的.txt").getId();

        Files.writeString(folder.resolve("a.txt"), "a");
        Long pid = openProject(folder);

        String out = tools.move_to_trash("[" + foreignId + ", \"查无此文件.txt\", \"a.txt\"]");

        assertTrue(out.startsWith("trashed: 1; failed: 2"), out);
        assertTrue(out.contains("FAILED"), out);
        assertFalse(Boolean.TRUE.equals(row(otherPid, "别人的.txt").getIsDeleted()), "跨项目 id 绝不许被软删");
        assertTrue(Boolean.TRUE.equals(row(pid, "a.txt").getIsDeleted()));
    }

    @Test
    @DisplayName("越界路径 / 超 50 项 / 坏 JSON：整批拒绝，一项都不动")
    void shapeErrorsRejectWholeBatch(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "a");
        Long pid = openProject(folder);

        assertTrue(tools.move_to_trash("[\"a.txt\", \"../别的项目/x.txt\"]").startsWith("Error"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) many.append(i == 0 ? "" : ",").append("\"f").append(i).append(".txt\"");
        assertTrue(tools.move_to_trash(many.append("]").toString()).contains("50"));
        assertTrue(tools.move_to_trash("不是 JSON").startsWith("Error"));
        assertTrue(tools.move_to_trash("[true]").startsWith("Error"));

        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty(), "形状错误时一项都不许先动手");
    }

    @Test
    @DisplayName("契约：file 类目、refreshFiles 由编排器刷一次、方法体不自己刷；delete_file 仍不下发")
    void toolMetaContract() throws Exception {
        ToolMeta meta = FileTools.class.getMethod("move_to_trash", String.class).getAnnotation(ToolMeta.class);
        assertNotNull(meta);
        assertTrue(meta.refreshFiles());
        assertTrue(meta.offerToModel());
        assertEquals("file", meta.category());
        ToolMeta del = FileTools.class.getMethod("delete_file", String.class).getAnnotation(ToolMeta.class);
        assertFalse(del.offerToModel(), "永久删除维持不下发");
        assertTrue(tools.delete_file("a.txt").contains("move_to_trash"), "XML 兜底调到 delete_file 时要指路");
        verify(bridge, never()).sendRefreshFilesAction();
    }

    @Test
    @DisplayName("两版 system prompt 与两版 tools-none 都教「清理用 move_to_trash、不建待删除文件夹」")
    void promptsTeachTrashInsteadOfToDeleteFolder() throws Exception {
        for (String name : List.of("prompts/system_prompt.md", "prompts/system_prompt.en.md",
                "prompts/tools-none.md", "prompts/tools-none.en.md")) {
            String text;
            try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(in, name + " 应存在");
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(text.contains("`move_to_trash`"), name + " 没教 move_to_trash");
            assertTrue(text.contains("待删除") || text.contains("to delete"), name + " 没说别建待删除文件夹");
        }
    }
}
