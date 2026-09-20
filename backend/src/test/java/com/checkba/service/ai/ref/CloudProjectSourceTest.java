// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.file.ProjectFileTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CloudProjectSource（dev-board#718）：云端项目文件作为参考材料，ref 形如 {@code cloud:<fileId>}。
 *
 * <p>要守住的东西：
 * <ol>
 *   <li>fileId 是模型抄来的参数，读之前必须按「这个人能不能读那个项目」判权，判不过一律同一句话，
 *       不回显别人项目的文件名；</li>
 *   <li>正文走与 extract_file_text 同一个抽取器；</li>
 *   <li>未打开的文件没有页的概念：带 locator 时明说返回的是全文，而不是悄悄忽略。</li>
 * </ol>
 */
class CloudProjectSourceTest {

    private final ProjectFileService fileService = mock(ProjectFileService.class);
    private final ProjectMemberService members = mock(ProjectMemberService.class);
    private final ProjectFileTextExtractor extractor = mock(ProjectFileTextExtractor.class);
    private final CloudProjectSource src = new CloudProjectSource(fileService, members, extractor);
    private final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    private static ProjectFile file(long id, Long projectId, String name) {
        ProjectFile pf = new ProjectFile();
        pf.setId(id);
        pf.setProjectId(projectId);
        pf.setName(name);
        pf.setIsFolder(false);
        return pf;
    }

    @Test
    void schemeIsCloud() {
        assertThat(src.scheme()).isEqualTo("cloud");
    }

    @Test
    void refusesFileFromUnreadableProject() throws Exception {
        ProjectFile pf = file(5L, 99L, "别人的合同.docx");
        when(fileService.getFile(5L)).thenReturn(pf);
        when(members.hasReadPermission(99L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> src.read(q, "5", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageNotContaining("别人的合同");
        verify(extractor, never()).extract(any());
    }

    @Test
    void missingOrMalformedFileIdIsARefSourceException() throws Exception {
        when(fileService.getFile(6L)).thenThrow(new IllegalArgumentException("文件不存在: 6"));

        assertThatThrownBy(() -> src.read(q, "6", null)).isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.read(q, "abc", null)).isInstanceOf(RefSourceException.class);
        assertThatThrownBy(() -> src.read(q, "", null)).isInstanceOf(RefSourceException.class);
        verify(extractor, never()).extract(any());
    }

    @Test
    void readsViaExtractorAndNotesLocatorIgnored() throws Exception {
        ProjectFile pf = file(5L, 11L, "C.docx");
        when(fileService.getFile(5L)).thenReturn(pf);
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(extractor.extract(pf)).thenReturn("正文");

        assertThat(src.read(q, "5", "page:2")).startsWith("未打开的文件无法按页定位，以下为全文").endsWith("正文");
        assertThat(src.read(q, "5", null)).isEqualTo("正文");
        assertThat(src.read(q, " 5 ", "  ")).isEqualTo("正文");
    }

    @Test
    void emptyFileDoesNotGetTheFullTextNote() throws Exception {
        ProjectFile pf = file(5L, 11L, "空白.docx");
        when(fileService.getFile(5L)).thenReturn(pf);
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(extractor.extract(pf)).thenReturn("");

        // 抽不出一个字时不能回「以下为全文」再接一段空白——模型会把它当成「这份文件是空的全文」来引用。
        // 空白交给 ReferenceSourceService 统一换成「该文件没有可读取的文字。」（它的测试守着那一句）。
        assertThat(src.read(q, "5", "page:2")).isBlank();
        assertThat(src.read(q, "5", null)).isBlank();
    }

    @Test
    void extractorFailureIsPassedOnAsTheReason() throws Exception {
        ProjectFile pf = file(5L, 11L, "大卷宗.pdf");
        when(fileService.getFile(5L)).thenReturn(pf);
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(extractor.extract(pf)).thenThrow(new IOException("文件超过 50MB，暂不支持作为参考材料读取"));

        assertThatThrownBy(() -> src.read(q, "5", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("50MB");
    }

    @Test
    void listsCurrentProjectFiles() {
        ProjectFile pf = file(5L, 11L, "C.docx");
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(fileService.listRelativePaths(11L, null, 100)).thenReturn(List.of(Map.entry("资料/C.docx", pf)));

        List<RefEntry> entries = src.list(q);

        assertThat(entries).extracting(RefEntry::ref).containsExactly("cloud:5");
        assertThat(entries).extracting(RefEntry::path).containsExactly("资料/C.docx");
        assertThat(entries).extracting(RefEntry::source).containsExactly("cloud");
    }

    @Test
    void listPassesTheKeywordThrough() {
        when(members.hasReadPermission(11L, 7L)).thenReturn(true);
        when(fileService.listRelativePaths(11L, "合同", 100)).thenReturn(List.of());

        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", "  合同 "))).isEmpty();
        verify(fileService).listRelativePaths(11L, "合同", 100);
    }

    @Test
    void listIsEmptyWithoutProjectOrPermission() {
        assertThat(src.list(new RefQuery(7L, null, "conv-a", null))).isEmpty();
        when(members.hasReadPermission(11L, 7L)).thenReturn(false);
        assertThat(src.list(q)).isEmpty();
        verify(fileService, never()).listRelativePaths(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cloudFilesAreReadOnly() {
        assertThatThrownBy(() -> src.edit(q, "5", "replace_text", Map.of()))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("没有打开");
    }
}
