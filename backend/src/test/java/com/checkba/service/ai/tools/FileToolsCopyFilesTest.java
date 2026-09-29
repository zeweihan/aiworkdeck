// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.dto.ProjectFileBatchRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * copy_files：界面上的「复制 / 粘贴」（{@link ProjectFileService#batchCopy}）做成工具（dev-board#1065，审计 T-25）。
 * 复制本身（文件夹递归、同名加前缀、物理文件）由 batchCopy 负责、有它自己的测试；这里钉的是
 * 参数解析、归属前置校验与回执。
 */
class FileToolsCopyFilesTest {

    private final ProjectFileService files = Mockito.mock(ProjectFileService.class);
    private final FileTools tools = new FileTools(files, null, null, null, null, null, null, null, null);

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private ProjectFile owned(long id, long projectId, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setIsFolder(false);
        when(files.findFile(id)).thenReturn(Optional.of(f));
        return f;
    }

    @Test
    @DisplayName("JSON 数组与逗号分隔都认，交给 batchCopy，回执列出新文件的 id")
    void copiesThroughTheServiceAndReportsNewIds() {
        ProjectContextHolder.setProjectId("7");
        owned(11L, 7L, "股权转让协议.docx");
        owned(12L, 7L, "补充协议.docx");
        ProjectFile c1 = new ProjectFile();
        c1.setId(101L);
        c1.setName("股权转让协议.docx");
        ProjectFile c2 = new ProjectFile();
        c2.setId(102L);
        c2.setName("补充协议.docx");
        when(files.batchCopy(eq(7L), any(), anyLong())).thenReturn(List.of(c1, c2));

        String out = tools.copy_files("[11, 12]", 30L);

        ArgumentCaptor<ProjectFileBatchRequest> req = ArgumentCaptor.forClass(ProjectFileBatchRequest.class);
        verify(files).batchCopy(eq(7L), req.capture(), anyLong());
        assertEquals(List.of(11L, 12L), req.getValue().getFileIds());
        assertEquals(30L, req.getValue().getTargetParentId());
        assertTrue(out.startsWith("copied: 2"), out);
        assertTrue(out.contains("股权转让协议.docx (fileId=101)"), out);
        assertTrue(out.contains("文件夹 30"), out);

        tools.copy_files("11，12", null);
        verify(files, Mockito.times(2)).batchCopy(eq(7L), any(), anyLong());
    }

    @Test
    @DisplayName("任何一项不属于本项目 / 不存在 / 已删除：整批不动手，回同一句话")
    void foreignOrMissingIdsRejectTheWholeBatch() {
        ProjectContextHolder.setProjectId("7");
        owned(11L, 7L, "a.docx");
        owned(13L, 999L, "别人的.docx");
        ProjectFile deleted = owned(14L, 7L, "已删.docx");
        deleted.setIsDeleted(true);

        assertTrue(tools.copy_files("[11, 13]", null).startsWith("Error: fileId 13 is not a file of this project"));
        assertTrue(tools.copy_files("[11, 15]", null).startsWith("Error: fileId 15 is not a file of this project"));
        assertTrue(tools.copy_files("[14]", null).startsWith("Error:"));
        verify(files, never()).batchCopy(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("形状不合法：空、非数字、超过 50 项、没有项目上下文——Error 开头")
    void malformedInputIsRejected() {
        ProjectContextHolder.setProjectId("7");
        assertTrue(tools.copy_files("", null).startsWith("Error:"));
        assertTrue(tools.copy_files("[\"合同.docx\"]", null).startsWith("Error:"));
        assertTrue(tools.copy_files("[0]", null).startsWith("Error:"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 1; i <= 51; i++) {
            many.append(i).append(i < 51 ? "," : "]");
        }
        assertTrue(tools.copy_files(many.toString(), null).contains("一次最多复制 50 项"));
        ProjectContextHolder.clear();
        assertTrue(tools.copy_files("[1]", null).startsWith("Error:"));
        verify(files, never()).batchCopy(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("服务层的拒绝（目标文件夹不存在等）原样转成 Error 回给模型")
    void serviceRejectionIsRelayed() {
        ProjectContextHolder.setProjectId("7");
        owned(11L, 7L, "a.docx");
        when(files.batchCopy(eq(7L), any(), anyLong()))
                .thenThrow(new IllegalArgumentException("目标文件夹不存在或已被删除: 99"));
        assertEquals("Error: 目标文件夹不存在或已被删除: 99", tools.copy_files("[11]", 99L));
    }

    @Test
    @DisplayName("声明：刷新文件树、不声明 fileEffect（没有哪一份文件被改动）")
    void declaresATreeRefreshOnly() throws Exception {
        var m = FileTools.class.getMethod("copy_files", String.class, Long.class);
        assertTrue(m.isAnnotationPresent(Tool.class));
        ToolMeta meta = m.getAnnotation(ToolMeta.class);
        assertTrue(meta.refreshFiles());
        assertEquals("", meta.fileEffect());
        assertTrue(meta.offerToModel());
    }
}
