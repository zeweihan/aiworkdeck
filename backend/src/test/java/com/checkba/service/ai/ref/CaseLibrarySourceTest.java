// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.AccountBinding;
import com.checkba.repository.AccountBindingRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 官方案件库作为只读参考来源（dev-board#720），ref 形如 {@code case:<remoteProjectId>:<path>}。
 *
 * <p>要守住的东西：
 * <ol>
 *   <li>跨实例身份键只认 {@code AccountBinding.externalAccountId}——没有绑定就没有身份，
 *       来源直接缺席，一次请求都不发（国际站与自建服务器的默认态）；</li>
 *   <li>未打开的文件没有页的概念：带 locator 时明说返回的是全文；</li>
 *   <li>案件库那侧说得出原因的失败（没权限、文件太大）原样转述，传输故障只说「暂时无法访问」，
 *       不把网络细节喂给模型；</li>
 *   <li>只读：edit / open 一律拒绝。</li>
 * </ol>
 */
class CaseLibrarySourceTest {

    private final CaseRefClient client = mock(CaseRefClient.class);
    private final AccountBindingRepository bindings = mock(AccountBindingRepository.class);
    private final CaseLibrarySource src = new CaseLibrarySource(client, bindings);
    private final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    private static AccountBinding binding(String externalAccountId) {
        AccountBinding b = new AccountBinding();
        b.setId(1L);
        b.setUserId(7L);
        b.setExternalAccountId(externalAccountId);
        return b;
    }

    private void bound() {
        when(client.configured()).thenReturn(true);
        when(bindings.findByUserId(7L)).thenReturn(Optional.of(binding("acc-1")));
    }

    @Test
    void schemeIsCase() {
        assertThat(src.scheme()).isEqualTo("case");
    }

    @Test
    void unavailableWithoutBinding() {
        when(client.configured()).thenReturn(true);
        when(bindings.findByUserId(7L)).thenReturn(Optional.empty());

        assertThat(src.available(q)).isFalse();
    }

    @Test
    void unavailableWithoutConfiguredCaseInstance() throws Exception {
        when(client.configured()).thenReturn(false);

        assertThat(src.available(q)).isFalse();
        // 没配 = 本服务器没有案件库，连"这个人是谁"都不必查
        verifyNoInteractions(bindings);
    }

    @Test
    void availableWhenConfiguredAndBound() {
        bound();
        assertThat(src.available(q)).isTrue();
    }

    @Test
    void listMapsEntriesToCaseRefs() throws Exception {
        bound();
        when(client.list("acc-1", null)).thenReturn(List.of(
                new CaseRefClient.Entry(3L, "王某诉李某", "资料/说明.txt", "说明.txt")));

        List<RefEntry> out = src.list(q);

        assertThat(out).hasSize(1);
        RefEntry e = out.get(0);
        assertThat(e.ref()).isEqualTo("case:3:资料/说明.txt");
        assertThat(e.source()).isEqualTo("case");
        assertThat(e.name()).isEqualTo("说明.txt");
        assertThat(e.path()).isEqualTo("案件库/王某诉李某/资料/说明.txt");
    }

    @Test
    void listPassesKeywordThrough() throws Exception {
        when(client.configured()).thenReturn(true);
        when(bindings.findByUserId(7L)).thenReturn(Optional.of(binding("acc-1")));
        when(client.list("acc-1", "说明")).thenReturn(List.of());

        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", "说明"))).isEmpty();
        verify(client).list("acc-1", "说明");
    }

    @Test
    void readUsesExternalAccountIdAndLocatorNote() throws Exception {
        bound();
        when(client.read("acc-1", 3L, "资料/说明.txt")).thenReturn("说明正文");

        assertThat(src.read(q, "3:资料/说明.txt", "page:1"))
                .startsWith("未打开的文件无法按页定位")
                .contains("说明正文");
        assertThat(src.read(q, "3:资料/说明.txt", null)).isEqualTo("说明正文");
    }

    @Test
    void readKeepsColonsInsidePath() throws Exception {
        bound();
        when(client.read("acc-1", 3L, "资料/2026:一审/说明.txt")).thenReturn("说明正文");

        assertThat(src.read(q, "3:资料/2026:一审/说明.txt", null)).isEqualTo("说明正文");
    }

    @Test
    void malformedRefIsRefSourceException() throws Exception {
        bound();

        assertThatThrownBy(() -> src.read(q, "资料/说明.txt", null))
                .isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.read(q, "abc:资料/说明.txt", null))
                .isInstanceOf(RefSourceException.class);
        verify(client, never()).read(anyString(), anyLong(), anyString());
    }

    @Test
    void transportFailuresBecomeRefSourceException() throws Exception {
        bound();
        when(client.read("acc-1", 3L, "资料/说明.txt"))
                .thenThrow(new IOException("Connection refused: 127.0.0.1:9797"));

        assertThatThrownBy(() -> src.read(q, "3:资料/说明.txt", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("案件库暂时无法访问")
                // 传输细节不进模型的输入
                .hasMessageNotContaining("Connection refused");
    }

    @Test
    void caseSideReasonIsPassedThroughVerbatim() throws Exception {
        bound();
        when(client.read("acc-1", 3L, "资料/说明.txt"))
                .thenThrow(new CaseRefClient.CaseRefException("你没有这份案卷的读取权限。"));

        assertThatThrownBy(() -> src.read(q, "3:资料/说明.txt", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessage("你没有这份案卷的读取权限。");
    }

    @Test
    void listFailuresBecomeRefSourceException() throws Exception {
        bound();
        when(client.list("acc-1", null)).thenThrow(new IOException("timeout"));

        assertThatThrownBy(() -> src.list(q))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("案件库暂时无法访问");
    }

    @Test
    void caseLibraryIsReadOnly() {
        assertThatThrownBy(() -> src.edit(q, "3:资料/说明.txt", "replace", Map.of()))
                .isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.open(q, "3:资料/说明.txt"))
                .isInstanceOf(RefSourceException.class);
    }
}
