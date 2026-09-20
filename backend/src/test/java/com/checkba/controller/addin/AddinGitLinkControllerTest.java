// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.addin;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.controller.AuthController;
import com.checkba.model.entity.AddinGitRepoLink;
import com.checkba.repository.AddinGitRepoLinkRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.addin.GitProviderClient;
import com.checkba.service.addin.GitTokenCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/addin/git-links 的 HTTP 面（dev-board#720）。
 *
 * <p>要守住的几条：
 * <ol>
 *   <li>令牌只进不出：落库前加密，任何响应里都只有 tokenLast4；</li>
 *   <li>保存前真去仓库验一次——存下一个连不上的关联，用户要到某次对话里才发现；</li>
 *   <li>密钥没配时明说「服务器未配置」，不静默明文落库；</li>
 *   <li>别人的项目、别人的关联行一律碰不到。</li>
 * </ol>
 */
class AddinGitLinkControllerTest {

    private final AddinGitRepoLinkRepository links = mock(AddinGitRepoLinkRepository.class);
    private final GitProviderClient client = mock(GitProviderClient.class);
    private final GitTokenCipher cipher = mock(GitTokenCipher.class);
    private final ProjectMemberService members = mock(ProjectMemberService.class);

    private MockMvc mvc;
    private MockedStatic<AuthController> auth;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AddinGitLinkController(links, client, cipher, members)).build();
        auth = mockStatic(AuthController.class);
        // Mockito 对 Long 的默认返回值是 0L 而不是 null，未登录分支必须显式桩成 null
        auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(null);
        auth.when(() -> AuthController.getUserIdFromSession("awdt_good")).thenReturn(7L);
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(cipher.enabled()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenReturn("enc");
        when(links.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    private static final String BODY = """
            {"projectId":11,"url":"https://github.com/acme/docs","branch":"","token":"ghp_abcdef"}""";

    private static AddinGitRepoLink row(long id, long userId) {
        AddinGitRepoLink l = new AddinGitRepoLink();
        l.setId(id);
        l.setUserId(userId);
        l.setCloudProjectId(11L);
        l.setProvider("github");
        l.setOwner("acme");
        l.setRepo("docs");
        l.setBranch("main");
        l.setTokenEnc("enc");
        l.setTokenLast4("cdef");
        l.setLastError("令牌失效");
        return l;
    }

    // ==================== 鉴权与权限 ====================

    @Test
    void unauthenticatedIsRejectedEverywhere() throws Exception {
        mvc.perform(post("/api/addin/git-links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
        mvc.perform(get("/api/addin/git-links").param("projectId", "11"))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
        mvc.perform(delete("/api/addin/git-links/4"))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
        verifyNoInteractions(links, client, cipher);
    }

    @Test
    void otherPeoplesProjectIsRefusedBeforeTheTokenLeavesTheServer() throws Exception {
        when(members.hasReadPermission(99L, 7L)).thenReturn(false);
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":99,\"url\":\"https://github.com/acme/docs\",\"token\":\"t\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(client);
        verify(links, never()).save(any());
    }

    // ==================== 新增 ====================

    @Test
    void savesEncryptedTokenAndNeverEchoesIt() throws Exception {
        when(client.verify(any(), anyString()))
                .thenReturn(new GitProviderClient.RepoRef("github", "acme", "docs", "main"));
        when(links.findByUserIdAndCloudProjectIdAndProviderAndOwnerAndRepo(7L, 11L, "github", "acme", "docs"))
                .thenReturn(Optional.empty());

        String out = mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.link.provider").value("github"))
                .andExpect(jsonPath("$.link.owner").value("acme"))
                .andExpect(jsonPath("$.link.repo").value("docs"))
                // 分支留空 = 取仓库默认分支，落库的是解析后的那个
                .andExpect(jsonPath("$.link.branch").value("main"))
                .andExpect(jsonPath("$.link.tokenLast4").value("cdef"))
                .andReturn().getResponse().getContentAsString();
        assertThat(out).doesNotContain("ghp_abcdef").doesNotContain("tokenEnc").doesNotContain("enc");

        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getTokenEnc()).isEqualTo("enc");
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getCloudProjectId()).isEqualTo(11L);
        verify(cipher).encrypt("ghp_abcdef");
    }

    @Test
    void relinkingTheSameRepoUpdatesTheExistingRowInsteadOfAddingOne() throws Exception {
        AddinGitRepoLink existing = row(4L, 7L);
        when(client.verify(any(), anyString()))
                .thenReturn(new GitProviderClient.RepoRef("github", "acme", "docs", "main"));
        when(links.findByUserIdAndCloudProjectIdAndProviderAndOwnerAndRepo(7L, 11L, "github", "acme", "docs"))
                .thenReturn(Optional.of(existing));

        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.link.id").value(4));

        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(4L);
        // 重新填了令牌就是来修失效的：旧的失败原因必须清掉，不然面板永远挂着红字
        assertThat(saved.getValue().getLastError()).isNull();
    }

    @Test
    void verificationFailureIsReportedAndNothingIsStored() throws Exception {
        // 再次桩同一个方法要用 doThrow：when(...) 会先把它真调一遍，当场被上一条桩抛出来
        doThrow(new GitProviderClient.GitAuthException("401")).when(client).verify(any(), anyString());
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("令牌")));
        verify(links, never()).save(any());

        doThrow(new FileNotFoundException("404")).when(client).verify(any(), anyString());
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(jsonPath("$.code").value(400));
        verify(links, never()).save(any());
    }

    @Test
    void unsupportedHostIsRejectedWithoutSendingTheToken() throws Exception {
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":11,\"url\":\"https://gitlab.com/a/b\",\"token\":\"t\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Gitee")));
        verifyNoInteractions(client);
    }

    @Test
    void withoutCipherSecretTheTokenIsRefusedNotStoredInClear() throws Exception {
        when(cipher.enabled()).thenReturn(false);
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("令牌密钥")));
        verify(links, never()).save(any());
        verifyNoInteractions(client);
    }

    /** 公开仓库不需要令牌：没有令牌就没什么要加密的，密钥没配也照样能关联。 */
    @Test
    void publicRepoWithoutTokenWorksEvenWithoutCipherSecret() throws Exception {
        when(cipher.enabled()).thenReturn(false);
        when(client.verify(any(), any()))
                .thenReturn(new GitProviderClient.RepoRef("github", "acme", "docs", "main"));
        when(links.findByUserIdAndCloudProjectIdAndProviderAndOwnerAndRepo(anyLong(), anyLong(), anyString(),
                anyString(), anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":11,\"url\":\"https://github.com/acme/docs\",\"token\":\"\"}"))
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getTokenEnc()).isNull();
        assertThat(saved.getValue().getTokenLast4()).isNull();
    }

    @Test
    void missingProjectIsAnExplainableError() throws Exception {
        mvc.perform(post("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://github.com/acme/docs\",\"token\":\"t\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(client);
    }

    // ==================== 清单与解除 ====================

    @Test
    void listReturnsLinksWithoutAnyTokenMaterial() throws Exception {
        when(links.findByUserIdAndCloudProjectId(7L, 11L)).thenReturn(List.of(row(4L, 7L)));
        String out = mvc.perform(get("/api/addin/git-links")
                        .header("X-Session-Id", "awdt_good")
                        .param("projectId", "11"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.links[0].id").value(4))
                .andExpect(jsonPath("$.links[0].tokenLast4").value("cdef"))
                .andExpect(jsonPath("$.links[0].lastError").value("令牌失效"))
                .andReturn().getResponse().getContentAsString();
        assertThat(out).doesNotContain("tokenEnc");
    }

    @Test
    void deleteOnlyTouchesOwnRows() throws Exception {
        when(links.findByIdAndUserId(4L, 7L)).thenReturn(Optional.of(row(4L, 7L)));
        mvc.perform(delete("/api/addin/git-links/4").header("X-Session-Id", "awdt_good"))
                .andExpect(jsonPath("$.code").value(0));
        verify(links).delete(any());

        when(links.findByIdAndUserId(9L, 7L)).thenReturn(Optional.empty());
        mvc.perform(delete("/api/addin/git-links/9").header("X-Session-Id", "awdt_good"))
                .andExpect(jsonPath("$.code").value(0));
        // 实现删的是实体（links.delete(entity)）：断言「总共只删过那一行」。
        // 换成 never().deleteById(...) 是句空话——实现根本不调它，第二次真删了也照样绿
        verify(links, times(1)).delete(any());
    }
}
