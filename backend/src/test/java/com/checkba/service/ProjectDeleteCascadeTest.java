package com.checkba.service;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.ProjectMemory;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.model.entity.ProjectTask;
import com.checkba.model.entity.ProjectVariable;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectMemoryRepository;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.ProjectTaskRepository;
import com.checkba.repository.ProjectVariableRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.version.ProjectRepoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删项目必须连带清掉项目级的库行与磁盘目录。
 *
 * 这些表全是裸 Long 的 projectId（没有 JPA 关联），ddl-auto=update 也不会生成外键，
 * 数据库层没有 ON DELETE CASCADE——级联全靠 ProjectService.deleteProject 手写，
 * 所以只能在这一层守。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-delete-cascade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "storage.local.root-path=target/test-storage-delete-cascade"
})
@ActiveProfiles("desktop")
class ProjectDeleteCascadeTest {

    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ProjectFileRepository fileRepository;
    @Autowired private ProjectVariableRepository variableRepository;
    @Autowired private ProjectProfileFieldRepository profileFieldRepository;
    @Autowired private ProjectMemoryRepository memoryRepository;
    @Autowired private ProjectInvitationRepository invitationRepository;
    @Autowired private ProjectRemoteRepository remoteRepository;
    @Autowired private ProjectTaskRepository taskRepository;
    @Autowired private ProjectAiMessageRepository aiMessageRepository;
    @Autowired private EvidenceLinkRepository evidenceLinkRepository;
    @Autowired private EvidenceLinkTargetRepository evidenceLinkTargetRepository;
    @Autowired private ProjectStorageResolver storageResolver;
    @Autowired private ProjectRepoService projectRepoService;

    @Test
    void deleteProject_clearsEveryProjectScopedTableAndOnDiskDirectory() throws IOException {
        Long projectId = seedProject();

        Path projectDir = storageResolver.projectRoot(projectId);
        Path gitDir = projectRepoService.gitDir(projectId);
        Files.createDirectories(projectDir.resolve("客户上传"));
        Files.writeString(projectDir.resolve("客户上传").resolve("尽调材料.txt"), "机密");
        Files.createDirectories(gitDir.resolve("objects"));

        projectService.deleteProject(projectId);

        assertFalse(projectRepository.existsById(projectId), "project 行应被删除");
        assertTrue(memberRepository.findByProjectId(projectId).isEmpty(), "project_member 残留孤儿行");
        assertTrue(fileRepository.findByProjectId(projectId).isEmpty(), "project_file 残留孤儿行");
        assertTrue(variableRepository.findByProjectId(projectId).isEmpty(), "project_variables 残留孤儿行");
        assertTrue(profileFieldRepository.findByProjectId(projectId).isEmpty(), "project_profile_field 残留孤儿行");
        assertTrue(memoryRepository.findByProjectId(projectId).isEmpty(), "project_memory 残留孤儿行");
        assertTrue(invitationRepository.findByProjectIdAndType(projectId, "CLIENT").isEmpty(), "project_invitation 残留孤儿行");
        assertTrue(remoteRepository.findByProjectId(projectId).isEmpty(), "project_remote 残留孤儿行");
        assertTrue(taskRepository.findAll().stream().noneMatch(t -> projectId.equals(t.getProjectId())), "project_task 残留孤儿行");
        assertTrue(aiMessageRepository.findByProjectIdOrderByCreatedAtAsc(projectId).isEmpty(), "project_ai_message 残留孤儿行");
        assertTrue(evidenceLinkRepository.findByProjectIdAndDocFileIdOrderByIdAsc(projectId, seededDocFileId).isEmpty(), "evidence_link 残留孤儿行");
        assertTrue(evidenceLinkTargetRepository.findByFileId(seededEvidenceFileId).isEmpty(), "evidence_link_target 残留孤儿行（没有 project_id，要按 link id 级联）");

        assertFalse(Files.exists(projectDir), "项目目录仍留在磁盘上: " + projectDir);
        assertFalse(Files.exists(gitDir), "版本记录仓库仍留在磁盘上: " + gitDir);
    }

    /** localRoot 非空的 IDE 化项目，目录是用户自己的文件夹，只能解绑不能删。 */
    @Test
    void deleteProject_keepsUserOwnedLocalRootDirectory() throws IOException {
        Path userFolder = Path.of("target", "test-local-root-delete-cascade").toAbsolutePath();
        Files.createDirectories(userFolder);
        Files.writeString(userFolder.resolve("用户自己的文件.txt"), "别删我");

        Project project = new Project();
        project.setName("本地文件夹项目");
        project.setProjectType("BLANK");
        project.setListedCompanyName("");
        project.setTargetCompanyName("");
        project.setUserId(9001L);
        project.setLocalRoot(userFolder.toString());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        Long projectId = projectRepository.save(project).getId();

        projectService.deleteProject(projectId);

        assertFalse(projectRepository.existsById(projectId));
        assertTrue(Files.exists(userFolder.resolve("用户自己的文件.txt")), "用户自己的文件夹被误删");
    }

    private Long seededDocFileId;
    private Long seededEvidenceFileId;

    private Long seedProject() {
        Project project = new Project();
        project.setName("待删项目");
        project.setProjectType("BLANK");
        project.setListedCompanyName("");
        project.setTargetCompanyName("");
        project.setUserId(9000L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        Long projectId = projectRepository.save(project).getId();

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(9000L);
        member.setRole("ADMIN");
        memberRepository.save(member);

        ProjectFile file = new ProjectFile();
        file.setProjectId(projectId);
        file.setIsFolder(false);
        file.setName("尽调材料.txt");
        file.setSortOrder(0);
        file.setUserId(9000L);
        file.setIsDeleted(false);
        file.setFilePath("projects/" + projectId + "/客户上传/尽调材料.txt");
        seededEvidenceFileId = fileRepository.save(file).getId();
        seededDocFileId = seededEvidenceFileId + 100_000L; // 报告文档 id 只是裸 Long，不必真有行

        EvidenceLink link = new EvidenceLink();
        link.setProjectId(projectId);
        link.setDocFileId(seededDocFileId);
        link.setLinkKey("EVID_DELETECASCADE0000000000001");
        link.setAnchorText("收购人成立于 2020 年");
        link.setCreatedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        Long linkId = evidenceLinkRepository.save(link).getId();

        EvidenceLinkTarget target = new EvidenceLinkTarget();
        target.setLinkId(linkId);
        target.setFileId(seededEvidenceFileId);
        target.setCreatedAt(LocalDateTime.now());
        evidenceLinkTargetRepository.save(target);

        ProjectVariable variable = new ProjectVariable();
        variable.setProjectId(projectId);
        variable.setName("公司名");
        variable.setValue("某某有限公司");
        variable.setType("TEXT");
        variableRepository.save(variable);

        ProjectProfileField field = new ProjectProfileField();
        field.setProjectId(projectId);
        field.setFieldKey("client");
        field.setFieldValue("某某");
        field.setSource("user");
        field.setUid(UUID.randomUUID().toString());
        profileFieldRepository.save(field);

        ProjectMemory memory = new ProjectMemory();
        memory.setProjectId(projectId);
        memory.setProjectName("待删项目");
        memoryRepository.save(memory);

        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(projectId);
        invitation.setAccessCode("CODE" + projectId);
        invitation.setType("CLIENT");
        invitation.setRelatedUserId(9002L);
        invitationRepository.save(invitation);

        ProjectRemote remote = new ProjectRemote();
        remote.setProjectId(projectId);
        remote.setConnectionId(1L);
        remote.setPendingUpload(false);
        remote.setCreatedAt(LocalDateTime.now());
        remoteRepository.save(remote);

        ProjectTask task = new ProjectTask();
        task.setUid(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setTitle("开庭");
        task.setDueDate(LocalDate.now());
        task.setStatus("OPEN");
        task.setSource("user");
        task.setUserId(9000L);
        taskRepository.save(task);

        ProjectAiMessage message = new ProjectAiMessage();
        message.setProjectId(projectId);
        message.setUserId(9000L);
        message.setRole("USER");
        message.setContent("你好");
        message.setCreatedAt(LocalDateTime.now());
        aiMessageRepository.save(message);

        return projectId;
    }
}
