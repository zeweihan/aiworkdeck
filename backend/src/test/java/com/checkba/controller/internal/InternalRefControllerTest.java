// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.internal;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.Project;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.file.ProjectFileTextExtractor;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.ProjectRepoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 官方案件库的内部端点（dev-board#720，spec §7.1）：插件云后端经 127.0.0.1 向 case 实例问
 * 「这个官网账号在案件库里能看到哪些文件 / 这一份的正文是什么」。
 *
 * <p>这是一条没有会话、只凭共享密钥说话的口子，所以三道闸各有一个用例、缺一不可：
 * 密钥未配置、密钥不符、来源不是回环地址——三者一律回<b>裸 404</b>，
 * 与公网 nginx 的 {@code ^~ /api/internal/} 兜底同一副面孔，不告诉扫描器"这里有个端点"。
 *
 * <p>另外两条：跨实例身份只认 {@code AccountBinding.externalAccountId}（认不出就是空清单，
 * 不是错误）；客户角色在案件库里读不到任何东西（与 {@code VersionController.requireMember}
 * 拒 CLIENT 同口径）。
 *
 * <p>仓库是真的 JGit 仓库（临时目录 + {@code ProjectRepoService.init}），读的是 HEAD 上的真实
 * blob——「读得出案件库里那份文件」这件事用 mock 证不了。
 */
class InternalRefControllerTest {

    private static final String SECRET = "s3cret";

    private AccountBindingRepository bindings;
    private ProjectService projects;
    private ProjectMemberService members;
    private ProjectRepoService repo;
    private ProjectFileTextExtractor extractor;
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path tempRoot) throws Exception {
        root = tempRoot;
        bindings = mock(AccountBindingRepository.class);
        projects = mock(ProjectService.class);
        members = mock(ProjectMemberService.class);

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repo = new ProjectRepoService(new ProjectStorageResolver(props, null));

        extractor = new ProjectFileTextExtractor(
                new DocumentTextService(mock(StorageServiceFactory.class)),
                mock(FileContentExtractorService.class),
                mock(ProjectFileService.class));
    }

    private MockMvc mvc(boolean serve, String secret) {
        return MockMvcBuilders
                .standaloneSetup(new InternalRefController(serve, secret, bindings, projects, members, repo, extractor))
                .build();
    }

    private MockMvc mvc(String secret) {
        return mvc(true, secret);
    }

    private MockMvc mvc() {
        return mvc(SECRET);
    }

    private static Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setUserId(7L);
        return p;
    }

    /** 建一个真的 JGit 仓库，HEAD 上带给定的文件。 */
    private void seed(long projectId, String relPath, String content) throws Exception {
        Path file = root.resolve("projects").resolve(String.valueOf(projectId)).resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        repo.init(projectId, "韩泽伟", "hzw@example.com");
    }

    private void boundTo(String externalAccountId, Long userId) {
        AccountBinding b = new AccountBinding();
        b.setId(1L);
        b.setUserId(userId);
        b.setExternalAccountId(externalAccountId);
        when(bindings.findByExternalAccountId(externalAccountId)).thenReturn(Optional.of(b));
    }

    // ==================== 四道闸 ====================

    /**
     * 密钥是**一把两用**的：addin 实例把它当出站头 X-Internal-Secret，按部署文档
     * /opt/aiworkdeck/cloud/env 里也配着同一个值。所以「配了密钥」绝不能等于
     * 「本实例对外提供这条口子」——只有 case profile 打开 ref.internal.serve，
     * addin 那个 JVM 里这两个端点恒回裸 404，哪怕请求来自回环且密钥完全正确。
     */
    @Test
    void notServingIs404EvenWithRightSecretFromLoopback() throws Exception {
        mvc(false, SECRET).perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"a.txt\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mvc(false, SECRET).perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isNotFound());
        verify(bindings, never()).findByExternalAccountId(anyString());
    }

    @Test
    void missingSecretConfigIs404() throws Exception {
        mvc("").perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verify(bindings, never()).findByExternalAccountId(anyString());
    }

    @Test
    void wrongSecretIs404() throws Exception {
        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", "s3cre")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"a.txt\"}"))
                .andExpect(status().isNotFound());
        verify(bindings, never()).findByExternalAccountId(anyString());
    }

    @Test
    void missingSecretHeaderIs404() throws Exception {
        mvc().perform(post("/api/internal/ref/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonLoopbackIs404() throws Exception {
        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.5");
                            return request;
                        }))
                .andExpect(status().isNotFound());
        verify(bindings, never()).findByExternalAccountId(anyString());
    }

    @Test
    void ipv6LoopbackIsAccepted() throws Exception {
        when(bindings.findByExternalAccountId("acc-1")).thenReturn(Optional.empty());

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}")
                        .with(request -> {
                            request.setRemoteAddr("::1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== list ====================

    @Test
    void unknownAccountReturnsEmptyList() throws Exception {
        when(bindings.findByExternalAccountId("acc-nope")).thenReturn(Optional.empty());

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-nope\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries.length()").value(0));
        verify(projects, never()).getUserProjects(anyLong());
    }

    @Test
    void listFiltersByKeywordAcrossReadableProjects() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        seed(4L, "合同.txt", "合同正文");
        when(projects.getUserProjects(7L)).thenReturn(List.of(project(3L, "王某诉李某"), project(4L, "另一案")));

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"keyword\":\"说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].remoteProjectId").value(3))
                .andExpect(jsonPath("$.entries[0].projectName").value("王某诉李某"))
                .andExpect(jsonPath("$.entries[0].path").value("资料/说明.txt"))
                .andExpect(jsonPath("$.entries[0].name").value("说明.txt"));
    }

    @Test
    void listWithoutKeywordReturnsEveryFileButNotTheInternalManifest() throws Exception {
        boundTo("acc-1", 7L);
        Path awd = root.resolve("projects/3/.awd");
        Files.createDirectories(awd);
        Files.writeString(awd.resolve("tree.json"), "{}");
        seed(3L, "资料/说明.txt", "说明正文");
        when(projects.getUserProjects(7L)).thenReturn(List.of(project(3L, "王某诉李某")));

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].path").value("资料/说明.txt"));
    }

    @Test
    void clientRoleSeesNothingInTheList() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        when(projects.getUserProjects(7L)).thenReturn(List.of(project(3L, "王某诉李某")));
        when(members.isClient(3L, 7L)).thenReturn(true);

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void projectWithoutVersionHistoryIsSkippedInsteadOfFailingTheWholeList() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        // 项目 9 从没开过版本记录：仓库目录根本不存在
        when(projects.getUserProjects(7L)).thenReturn(List.of(project(9L, "没开版本记录的案子"),
                project(3L, "王某诉李某")));

        mvc().perform(post("/api/internal/ref/list")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].remoteProjectId").value(3));
    }

    // ==================== read ====================

    @Test
    void readsHeadBlobAsText() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        when(members.hasReadPermission(3L, 7L)).thenReturn(true);

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"资料/说明.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.text").value("说明正文"));
    }

    @Test
    void clientRoleCannotRead() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        when(members.hasReadPermission(3L, 7L)).thenReturn(true);
        when(members.isClient(3L, 7L)).thenReturn(true);

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"资料/说明.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("没有")))
                .andExpect(jsonPath("$.text").doesNotExist());
    }

    @Test
    void readWithoutPermissionSaysTheSameThingAsAMissingFile() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        when(members.hasReadPermission(3L, 7L)).thenReturn(false);

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"资料/说明.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                // 不回显别人案卷里的文件名
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("说明.txt"))));
    }

    @Test
    void readOfAMissingPathIsABusinessErrorNotACrash() throws Exception {
        boundTo("acc-1", 7L);
        seed(3L, "资料/说明.txt", "说明正文");
        when(members.hasReadPermission(3L, 7L)).thenReturn(true);

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\"资料/不存在.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void readRefusesTheInternalManifest() throws Exception {
        boundTo("acc-1", 7L);
        Path awd = root.resolve("projects/3/.awd");
        Files.createDirectories(awd);
        Files.writeString(awd.resolve("tree.json"), "{\"nodes\":[]}");
        seed(3L, "资料/说明.txt", "说明正文");
        when(members.hasReadPermission(3L, 7L)).thenReturn(true);

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-1\",\"remoteProjectId\":3,\"path\":\".awd/tree.json\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void unknownAccountCannotRead() throws Exception {
        when(bindings.findByExternalAccountId("acc-nope")).thenReturn(Optional.empty());

        mvc().perform(post("/api/internal/ref/read")
                        .header("X-Internal-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalAccountId\":\"acc-nope\",\"remoteProjectId\":3,\"path\":\"a.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }
}
