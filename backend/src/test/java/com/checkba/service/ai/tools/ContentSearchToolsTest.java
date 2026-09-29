// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ContentSearchService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * search_project_content：把界面上早就有的全文检索（{@link ContentSearchService}）包成工具
 *（dev-board#1065，审计 T-03）。检索本身不在这里测——它有自己的测试；这里钉的是
 * 「参数怎么翻译成 SearchRequest」与「结果怎么变成模型能照着用的几行字」。
 */
class ContentSearchToolsTest {

    private final ContentSearchService search = Mockito.mock(ContentSearchService.class);
    private final ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
    private final ContentSearchTools tools = new ContentSearchTools(search, repo);

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static SearchResult.FileSearchResult file(long id, String name, int... lines) {
        List<SearchResult.MatchInfo> matches = new ArrayList<>();
        for (int line : lines) {
            matches.add(SearchResult.MatchInfo.builder().lineNumber(line)
                    .content("第八条 违约金为合同总价的百分之二十（第 " + line + " 行）").build());
        }
        return SearchResult.FileSearchResult.builder().fileId(id).fileName(name)
                .matchCount(matches.size()).matches(matches).build();
    }

    private static ProjectFile row(long id, String name, Long parentId) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName(name);
        f.setParentId(parentId);
        f.setProjectId(7L);
        return f;
    }

    @Test
    @DisplayName("命中逐行给出：文件名 (fileId=N) [项目内路径] 第 L 行：片段")
    void hitsCarryIdPathLineAndSnippet() {
        ProjectContextHolder.setProjectId("7");
        when(repo.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(7L))
                .thenReturn(List.of(row(3L, "卷宗", null), row(12L, "股权转让协议.docx", 3L)));
        when(search.searchContent(eq(7L), any())).thenReturn(SearchResult.builder()
                .totalMatches(2).totalFiles(1).results(List.of(file(12L, "股权转让协议.docx", 5, 40))).build());

        String out = tools.search_project_content("违约金", null, null, null);

        assertTrue(out.contains("- 股权转让协议.docx (fileId=12) [卷宗/股权转让协议.docx] 第 5 行：第八条 违约金"), out);
        assertTrue(out.contains("第 40 行"), out);
        assertTrue(out.contains("extract_file_text"), "要告诉模型下一步怎么读上下文：" + out);
    }

    @Test
    @DisplayName("参数翻译：正则 / 整词 / 类型（去点、去星号、小写、去重）")
    void parametersBecomeTheSearchRequest() {
        ProjectContextHolder.setProjectId("7");
        when(search.searchContent(eq(7L), any())).thenReturn(SearchResult.builder().results(List.of()).build());

        tools.search_project_content("第[一二三]条", true, true, ".DOCX, *.pdf，docx");

        ArgumentCaptor<SearchRequest> req = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).searchContent(eq(7L), req.capture());
        assertEquals("第[一二三]条", req.getValue().getQuery());
        assertTrue(req.getValue().isUseRegex());
        assertTrue(req.getValue().isWholeWord());
        assertEquals(List.of("docx", "pdf"), req.getValue().getFileTypes());
    }

    @Test
    @DisplayName("没有命中要明说，不许返回空白")
    void noMatchesIsSaidPlainly() {
        ProjectContextHolder.setProjectId("7");
        when(search.searchContent(eq(7L), any())).thenReturn(SearchResult.builder().results(List.of()).build());

        String out = tools.search_project_content("不存在的词", null, null, null);
        assertTrue(out.startsWith("No matches"), out);
        assertTrue(out.contains("search_project_files"), "要指出按文件名找的那条路：" + out);
    }

    @Test
    @DisplayName("坏正则、空查询、没有项目上下文：Error 开头，而且不去搜")
    void badInputIsRejectedBeforeSearching() {
        ProjectContextHolder.setProjectId("7");
        assertTrue(tools.search_project_content("第(一条", true, null, null).startsWith("Error:"));
        assertTrue(tools.search_project_content("  ", null, null, null).startsWith("Error:"));
        ProjectContextHolder.clear();
        assertTrue(tools.search_project_content("违约金", null, null, null).startsWith("Error:"));
        verify(search, never()).searchContent(any(), any());
    }

    @Test
    @DisplayName("最多列 50 处；多出来的给个数，片段截到 200 字")
    void hitsAreCappedAndCounted() {
        ProjectContextHolder.setProjectId("7");
        int[] lines = new int[60];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = i + 1;
        }
        SearchResult.FileSearchResult big = file(20L, "证据汇编.pdf", lines);
        big.getMatches().set(0, SearchResult.MatchInfo.builder().lineNumber(1).content("长".repeat(500)).build());
        SearchResult.FileSearchResult nameOnly = SearchResult.FileSearchResult.builder()
                .fileId(21L).fileName("违约金计算表.xlsx").matchCount(0).matches(List.of()).build();
        when(search.searchContent(eq(7L), any())).thenReturn(SearchResult.builder()
                .totalMatches(60).results(List.of(big, nameOnly)).build());

        String out = tools.search_project_content("违约金", null, null, null);

        long hitLines = out.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(ContentSearchTools.MAX_HITS, hitLines, out);
        assertTrue(out.contains("还有 11 处未列出"), "10 处正文 + 1 处文件名命中没列出来：" + out);
        assertFalse(out.contains("长".repeat(201)), "片段必须截到 200 字");
    }
    @Test
    @DisplayName("GBK 编码的中文 txt 也搜得到：纯文本按字节解码，不再交给 Tika 抽回空串（dev-board#1065）")
    void gbkPlainTextIsSearchable() throws Exception {
        ProjectContextHolder.setProjectId("7");
        String body = "甲方：北京某某科技有限公司\n第八条 违约责任：应向守约方支付合同总价百分之二十的违约金。\n";
        byte[] gbk = body.getBytes(java.nio.charset.Charset.forName("GBK"));
        ProjectFile txt = row(61L, "会议纪要.txt", null);
        txt.setFileType("txt");
        txt.setFilePath("projects/7/会议纪要.txt");
        txt.setIsDeleted(false);
        txt.setSortOrder(0);

        ProjectFileRepository realRepo = Mockito.mock(ProjectFileRepository.class);
        when(realRepo.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(7L)).thenReturn(List.of(txt));
        com.checkba.storage.StorageService storage = Mockito.mock(com.checkba.storage.StorageService.class);
        when(storage.load("projects/7/会议纪要.txt"))
                .thenAnswer(inv -> new org.springframework.core.io.ByteArrayResource(gbk));
        com.checkba.storage.StorageServiceFactory factory = Mockito.mock(com.checkba.storage.StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        ContentSearchService realSearch = new ContentSearchService(realRepo,
                Mockito.mock(com.checkba.repository.FileTagRepository.class),
                Mockito.mock(com.checkba.repository.TagRepository.class),
                new com.checkba.service.DocumentTextService(factory), null);
        try {
            String out = new ContentSearchTools(realSearch, realRepo).search_project_content("违约金", null, null, null);
            assertTrue(out.contains("会议纪要.txt (fileId=61)"), out);
            assertTrue(out.contains("第 2 行：第八条 违约责任"), "命中片段要是正确解码的中文：" + out);
        } finally {
            realSearch.shutdown();
        }
    }
}
