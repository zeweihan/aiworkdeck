package com.checkba.service.ai.tools;

import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.service.DdExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dd_export AI 工具：docFileId 缺省时的自动定位（唯一候选自动用、零/多候选报错）、
 * 正常/异常路径的摘要文案。核心导出逻辑在 DdExportServiceTest 里锁，这里只锁工具层包装。
 */
@ExtendWith(MockitoExtension.class)
class DdExportToolsTest {

    @Mock private DdExportService ddExportService;
    @Mock private EvidenceLinkRepository evidenceLinkRepository;

    private DdExportTools tools() {
        return new DdExportTools(ddExportService, evidenceLinkRepository);
    }

    @Test
    @DisplayName("docFileId 显式给出：直接透传，不查候选")
    void explicitDocFileIdSkipsResolution() {
        when(ddExportService.export(5L, 1L, 10L, "docket", "docx"))
                .thenReturn(new DdExportService.ExportResult(300L, "_交付件/底稿目录.docx", 3));
        String out = tools().dd_export("docket", "docx", 10L, 1L, 5L);
        assertTrue(out.contains("_交付件/底稿目录.docx"), out);
        assertTrue(out.contains("3"), out);
        verify(evidenceLinkRepository, never()).findDistinctDocFileIdsByProjectId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("docFileId 缺省、项目里只有一份带底稿关联的文档：自动使用它")
    void resolvesSoleCandidateWhenOmitted() {
        when(evidenceLinkRepository.findDistinctDocFileIdsByProjectId(1L)).thenReturn(List.of(10L));
        when(ddExportService.export(5L, 1L, 10L, "gaps", null))
                .thenReturn(new DdExportService.ExportResult(301L, "_交付件/缺口清单.docx", 0));
        String out = tools().dd_export("gaps", null, null, 1L, 5L);
        assertTrue(out.contains("_交付件/缺口清单.docx"), out);
    }

    @Test
    @DisplayName("docFileId 缺省、项目里没有任何证据链接：报错不猜")
    void errorsWhenNoCandidates() {
        when(evidenceLinkRepository.findDistinctDocFileIdsByProjectId(1L)).thenReturn(List.of());
        String out = tools().dd_export("docket", null, null, 1L, 5L);
        assertTrue(out.startsWith("Error"), out);
        verify(ddExportService, never()).export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("docFileId 缺省、项目里有多份带底稿关联的文档：报错并列出候选，不擅自挑一个")
    void errorsWhenMultipleCandidates() {
        when(evidenceLinkRepository.findDistinctDocFileIdsByProjectId(1L)).thenReturn(List.of(10L, 20L));
        String out = tools().dd_export("docket", null, null, 1L, 5L);
        assertTrue(out.startsWith("Error"), out);
        assertTrue(out.contains("10") && out.contains("20"), out);
        verify(ddExportService, never()).export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Service 抛出的错误原样透传给模型，不包装成别的话术")
    void propagatesServiceError() {
        when(ddExportService.export(5L, 1L, 10L, "docket", "docx"))
                .thenThrow(new IllegalArgumentException("文件不属于该项目: 10"));
        String out = tools().dd_export("docket", "docx", 10L, 1L, 5L);
        assertTrue(out.equals("Error: 文件不属于该项目: 10"), out);
    }
}
