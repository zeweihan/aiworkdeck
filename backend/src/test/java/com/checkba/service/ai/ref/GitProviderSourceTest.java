// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.AddinGitRepoLink;
import com.checkba.repository.AddinGitRepoLinkRepository;
import com.checkba.service.addin.GitProviderClient;
import com.checkba.service.addin.GitTokenCipher;
import com.checkba.service.file.ProjectFileTextExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 关联的 GitHub / Gitee 仓库作为只读参考来源（dev-board#720），ref 形如 {@code git:<linkId>:<path>}。
 *
 * <p>要守住的几条：
 * <ol>
 *   <li>只读：edit / open 一律拒绝（D 决策）；</li>
 *   <li>关联是按人按项目的——别人的 linkId 抄过来也读不到，且与「没有这个文件」同一句话，
 *       不回显别人仓库的存在；</li>
 *   <li>令牌失效要落到 {@code lastError} 上：不然用户只会看到 AI 反复说「找不到文件」，
 *       永远想不到是令牌过期了；</li>
 *   <li>未打开的文件没有页的概念：带 locator 时明说返回的是全文。</li>
 * </ol>
 */
class GitProviderSourceTest {

    private final AddinGitRepoLinkRepository links = mock(AddinGitRepoLinkRepository.class);
    private final GitProviderClient client = mock(GitProviderClient.class);
    private final GitTokenCipher cipher = mock(GitTokenCipher.class);
    private final ProjectFileTextExtractor extractor = mock(ProjectFileTextExtractor.class);
    private final GitProviderSource src = new GitProviderSource(links, client, cipher, extractor);

    private final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    private static AddinGitRepoLink link(long id, String owner, String repo) {
        AddinGitRepoLink l = new AddinGitRepoLink();
        l.setId(id);
        l.setUserId(7L);
        l.setCloudProjectId(11L);
        l.setProvider("github");
        l.setOwner(owner);
        l.setRepo(repo);
        l.setBranch("main");
        l.setTokenEnc("enc");
        l.setTokenLast4("cdef");
        return l;
    }

    private void linked(AddinGitRepoLink... rows) {
        when(links.findByUserIdAndCloudProjectId(7L, 11L)).thenReturn(List.of(rows));
        for (AddinGitRepoLink row : rows) {
            when(links.findByIdAndUserId(row.getId(), 7L)).thenReturn(Optional.of(row));
        }
        when(cipher.enabled()).thenReturn(true);
        when(cipher.decrypt("enc")).thenReturn("t0ken");
    }

    @Test
    void schemeIsGit() {
        assertThat(src.scheme()).isEqualTo("git");
    }

    // ==================== list ====================

    @Test
    void listPrefixesRefsWithLinkId() throws Exception {
        AddinGitRepoLink l = link(4L, "acme", "docs");
        linked(l);
        when(client.listPaths(any(), eq("t0ken"))).thenReturn(List.of("a/x.docx"));

        List<RefEntry> out = src.list(q);
        assertThat(out).singleElement()
                .satisfies(e -> {
                    assertThat(e.ref()).isEqualTo("git:4:a/x.docx");
                    assertThat(e.source()).isEqualTo("git");
                    assertThat(e.name()).isEqualTo("x.docx");
                    assertThat(e.path()).contains("acme/docs").contains("a/x.docx");
                });
    }

    @Test
    void listFiltersByKeywordOnFileName() throws Exception {
        linked(link(4L, "acme", "docs"));
        when(client.listPaths(any(), anyString()))
                .thenReturn(List.of("合同/主合同.docx", "README.md", "清单/明细.xlsx"));

        List<RefEntry> out = src.list(new RefQuery(7L, 11L, "conv-a", "合同"));
        assertThat(out).extracting(RefEntry::path).allSatisfy(p -> assertThat(p).contains("主合同.docx"));
        assertThat(out).hasSize(1);
    }

    @Test
    void listWithoutLinksAsksNothingAndReturnsEmpty() {
        when(links.findByUserIdAndCloudProjectId(7L, 11L)).thenReturn(List.of());
        assertThat(src.list(q)).isEmpty();
        verifyNoInteractions(client);
    }

    /** 一个仓库坏了不该把另一个仓库的清单也带走。 */
    @Test
    void oneBrokenRepoDoesNotHideTheOthers() throws Exception {
        AddinGitRepoLink good = link(4L, "acme", "docs");
        AddinGitRepoLink bad = link(5L, "acme", "old");
        linked(good, bad);
        when(client.listPaths(argThatRepo("docs"), anyString())).thenReturn(List.of("a.docx"));
        when(client.listPaths(argThatRepo("old"), anyString()))
                .thenThrow(new GitProviderClient.GitAuthException("401"));

        assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("git:4:a.docx");
        // 坏掉的那个仓库把原因记在自己那一行上，面板里看得见
        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(5L);
        assertThat(saved.getValue().getLastError()).isNotBlank();
    }

    /** 全都坏了才整条报出来，让模型能如实转述而不是说「没有这个文件」。 */
    @Test
    void allReposFailingSurfacesTheReason() throws Exception {
        linked(link(4L, "acme", "docs"));
        when(client.listPaths(any(), anyString())).thenThrow(new GitProviderClient.GitAuthException("401"));
        assertThatThrownBy(() -> src.list(q))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("重新填写令牌");
    }

    // ==================== read ====================

    @Test
    void readsBytesThroughTheSharedExtractor() throws Exception {
        AddinGitRepoLink l = link(4L, "acme", "docs");
        l.setLastError("上一次读失败了");
        linked(l);
        when(client.readFile(any(), eq("t0ken"), eq("a/x.docx")))
                .thenReturn("bytes".getBytes(StandardCharsets.UTF_8));
        when(extractor.extractBytes(eq("a/x.docx"), any())).thenReturn("正文");

        assertThat(src.read(q, "4:a/x.docx", null)).isEqualTo("正文");
        // 读成功要落时间戳，并把上一次的失败原因抹掉
        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getLastOkAt()).isNotNull();
        assertThat(saved.getValue().getLastError()).isNull();
    }

    @Test
    void locatorOnAnUnopenedFileIsAnnouncedNotSwallowed() throws Exception {
        linked(link(4L, "acme", "docs"));
        when(client.readFile(any(), anyString(), anyString()))
                .thenReturn("bytes".getBytes(StandardCharsets.UTF_8));
        when(extractor.extractBytes(anyString(), any())).thenReturn("正文");

        assertThat(src.read(q, "4:a/x.docx", "page:3")).contains("无法按页定位").contains("正文");
    }

    @Test
    void anotherUsersLinkReadsLikeAMissingFile() {
        when(links.findByIdAndUserId(4L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> src.read(q, "4:a/x.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("ref_list");
        verifyNoInteractions(client);
    }

    /**
     * 同一个人、另一个项目里的关联：ref 抄过来（上一轮别的项目的对话里复制的）也读不到。
     *
     * <p>list 那边本来就是按项目查的；read 只判人的话，那条项目边界就成了摆设——
     * 关联的唯一键里有 cloud_project_id，读也得按这两维判。
     */
    @Test
    void linkFromAnotherProjectReadsLikeAMissingFile() {
        AddinGitRepoLink l = link(4L, "acme", "docs");
        l.setCloudProjectId(12L);
        when(links.findByIdAndUserId(4L, 7L)).thenReturn(Optional.of(l));

        assertThatThrownBy(() -> src.read(q, "4:a/x.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("ref_list");
        verifyNoInteractions(client);
    }

    @Test
    void malformedRefIsRejected() {
        assertThatThrownBy(() -> src.read(q, "4", null)).isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.read(q, "x:a.docx", null)).isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.read(q, "4:", null)).isInstanceOf(RefSourceException.class);
        verifyNoInteractions(client);
    }

    @Test
    void authFailureRecordsLastErrorAndExplains() throws Exception {
        AddinGitRepoLink l = link(4L, "acme", "docs");
        linked(l);
        when(client.readFile(any(), anyString(), anyString()))
                .thenThrow(new GitProviderClient.GitAuthException("401 Bad credentials"));

        assertThatThrownBy(() -> src.read(q, "4:a/x.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("重新填写令牌");
        ArgumentCaptor<AddinGitRepoLink> saved = ArgumentCaptor.forClass(AddinGitRepoLink.class);
        verify(links).save(saved.capture());
        assertThat(saved.getValue().getLastError()).isNotBlank();
    }

    @Test
    void missingPathSaysSoWithoutTouchingLastError() throws Exception {
        linked(link(4L, "acme", "docs"));
        when(client.readFile(any(), anyString(), anyString())).thenThrow(new FileNotFoundException("404"));

        assertThatThrownBy(() -> src.read(q, "4:gone.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("ref_list");
        verify(links, never()).save(any());
    }

    @Test
    void transportFailureDoesNotLeakUpstreamDetail() throws Exception {
        linked(link(4L, "acme", "docs"));
        when(client.readFile(any(), anyString(), anyString()))
                .thenThrow(new IOException("connect to 10.0.0.1:443 refused"));

        assertThatThrownBy(() -> src.read(q, "4:a/x.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageNotContaining("10.0.0.1");
    }

    /** 密钥没配、库里却有加密令牌：说清是服务器没配，而不是让用户去换令牌。 */
    @Test
    void disabledCipherWithStoredTokenExplainsTheServerSide() {
        AddinGitRepoLink l = link(4L, "acme", "docs");
        when(links.findByIdAndUserId(4L, 7L)).thenReturn(Optional.of(l));
        when(cipher.enabled()).thenReturn(false);

        assertThatThrownBy(() -> src.read(q, "4:a/x.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("令牌密钥");
        verifyNoInteractions(client);
    }

    // ==================== 只读 ====================

    @Test
    void editAndOpenAreRefused() {
        assertThatThrownBy(() -> src.edit(q, "4:a.docx", "replace_text", Map.of()))
                .isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.open(q, "4:a.docx")).isInstanceOf(RefSourceException.class);
        verifyNoInteractions(client);
    }

    private static GitProviderClient.RepoRef argThatRepo(String repo) {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && repo.equals(r.repo()));
    }
}
