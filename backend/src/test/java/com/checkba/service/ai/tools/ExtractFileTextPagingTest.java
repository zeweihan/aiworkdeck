// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.file.ProjectFileTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extract_file_text 的续读（dev-board#1065，审计 T-04）。
 *
 * <p>病灶：id 式读取只有 fileId 一个参数、正文截到 8 万字符；截断文案给的两条路一条只对
 * 编辑器里打开的那份有效（doc_get_document_text），另一条（「先检索定位再读该段」）没有任何工具
 * 能做到——超过 8 万字符的未打开文件，后半段谁也读不到。现在照 office_get_text 的形状加
 * offset / maxChars，回执给 nextStart 与「还有 N 字符未读」。
 */
class ExtractFileTextPagingTest {

    private static final long FILE_ID = 77L;

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    /** 每个字符都能认出自己的位置：第 i 个字符是 'a' + (i / 1000 % 26)，逐段不同。 */
    private static String body(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i / 1000 % 26)));
        }
        return sb.toString();
    }

    private static FileTools toolsReturning(String text) throws Exception {
        ProjectContextHolder.setProjectId("7");
        ProjectFile pf = new ProjectFile();
        pf.setId(FILE_ID);
        pf.setProjectId(7L);
        pf.setName("卷宗汇编.docx");
        pf.setFileType("docx");
        pf.setIsFolder(false);
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(FILE_ID)).thenReturn(Optional.of(pf));
        ProjectFileTextExtractor extractor = Mockito.mock(ProjectFileTextExtractor.class);
        Mockito.when(extractor.extractText(pf)).thenReturn(text);
        return new FileTools(Mockito.mock(ProjectFileService.class), repo, null, null, null, null, null, null, extractor);
    }

    private static int nextStartOf(String out) {
        Matcher m = Pattern.compile("nextStart=(\\d+)").matcher(out);
        assertTrue(m.find(), "回执里要有 nextStart：" + out.substring(Math.max(0, out.length() - 200)));
        return Integer.parseInt(m.group(1));
    }

    @Test
    @DisplayName("超长文件：第一次给前 8 万字符 + nextStart，照着 offset 续读能一字不差地拼回全文")
    void offsetContinuationReassemblesTheWholeText() throws Exception {
        String full = body(200_000);
        FileTools tools = toolsReturning(full);

        StringBuilder reassembled = new StringBuilder();
        String out = tools.extract_file_text(FILE_ID, null, null);
        assertTrue(out.contains("已截断至前 80000 字符"), out.substring(0, 200));
        assertTrue(out.contains("extract_file_text(fileId=" + FILE_ID + ", offset=80000)"),
                "续读指引要把 fileId 与 offset 写全，模型照抄即可：" + out.substring(0, 300));
        int calls = 0;
        int offset = 0;
        while (true) {
            calls++;
            String bodyPart = out.substring(out.indexOf("]\n") + 2);
            int tail = bodyPart.lastIndexOf("\n[");
            reassembled.append(bodyPart, 0, tail);
            if (out.endsWith("[已读到文末]")) {
                break;
            }
            assertTrue(out.contains("字符未读"), "没读完就要明说还有多少：" + out.substring(out.length() - 200));
            offset = nextStartOf(out);
            out = tools.extract_file_text(FILE_ID, offset, null);
            assertTrue(calls < 10, "续读不收敛");
        }
        assertEquals(3, calls, "20 万字符按 8 万一页是三次");
        assertEquals(full, reassembled.toString(), "三段拼起来必须与原文逐字相同");
    }

    @Test
    @DisplayName("maxChars 收窄一次读多少，超过上限按上限")
    void maxCharsNarrowsThePage() throws Exception {
        String full = body(10_000);
        FileTools tools = toolsReturning(full);

        String out = tools.extract_file_text(FILE_ID, 2_000, 500);
        assertTrue(out.startsWith("[文件 卷宗汇编.docx，全文 10000 字符，本次返回第 2000–2500 字符。]\n"), out);
        assertTrue(out.contains(full.substring(2_000, 2_500)));
        assertEquals(2_500, nextStartOf(out));
        assertTrue(out.contains("还有 7500 字符未读"), out);

        String capped = tools.extract_file_text(FILE_ID, 0, 999_999);
        assertTrue(capped.startsWith("[文件 卷宗汇编.docx]\n"), "一万字符远在上限内，整篇原样返回：" + capped.substring(0, 60));
    }

    @Test
    @DisplayName("短文件从头读：行为与改动前逐字一致（只有「[文件 X]」抬头，没有任何分页噪声）")
    void shortFileIsUnchanged() throws Exception {
        FileTools tools = toolsReturning("第一条 转让标的");
        assertEquals("[文件 卷宗汇编.docx]\n第一条 转让标的", tools.extract_file_text(FILE_ID));
        assertEquals("[文件 卷宗汇编.docx]\n第一条 转让标的", tools.extract_file_text(FILE_ID, 0, null));
    }

    @Test
    @DisplayName("offset 越过文末：Error 开头，说清已经读完（不是文件坏了）")
    void offsetPastTheEndIsAnActionableError() throws Exception {
        FileTools tools = toolsReturning(body(1_000));
        String out = tools.extract_file_text(FILE_ID, 5_000, null);
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("已经读到文末"), out);
    }

    @Test
    @DisplayName("ToolFileGuard：切点不切半个字，未截断时原样返回同一个实例")
    void guardNeverSplitsASurrogatePair() {
        String emoji = "📄"; // 一个补充平面字符 = 两个 char
        StringBuilder sb = new StringBuilder();
        sb.append("x".repeat(ToolFileGuard.MAX_TOOL_TEXT_CHARS - 1)).append(emoji).append("tail");
        String out = ToolFileGuard.pageToolText("a.txt", 1L, sb.toString(), 0, null);
        String bodyPart = out.substring(out.indexOf("]\n") + 2, out.lastIndexOf("\n["));
        assertFalse(Character.isHighSurrogate(bodyPart.charAt(bodyPart.length() - 1)), "不许以半个代理对结尾");
        assertEquals(ToolFileGuard.MAX_TOOL_TEXT_CHARS - 1, nextStartOf(out));

        String small = "短文本";
        assertSame(small, ToolFileGuard.pageToolText("a.txt", 1L, small, null, null));
        assertSame(small, ToolFileGuard.capToolText("a.txt", 1L, small));
    }
}
