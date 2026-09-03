package com.checkba.service.mobile;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 手机中转影像 fileType 存量对账（dev-board#417）。
 *
 * <p>现场（本机 H2，2026-09-03 取证）：手机 9-02 传上来的
 * {@code 现场影像-20260902-191122-D160-d16044f3.jpg} 落库时 file_type 是
 * {@code image}，字节本身是完好的 JPEG。前端 {@code isFileTypeSupported} 的白名单里
 * 只有 jpg/jpeg/png…，于是文件树里点开它只弹「无法打开文件」。
 * 写入侧已改成落扩展名，但那张照片早已 ACK、中转区 blob 早已删除，
 * 取件轮询再也不会碰它——存量只能靠启动期对账救。
 */
class MediaFileTypeReconcilerTest {

    private ProjectFile file(Long id, String name, String fileType) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(235L);
        f.setName(name);
        f.setIsFolder(false);
        f.setIsDeleted(false);
        f.setFileType(fileType);
        return f;
    }

    private MediaFileTypeReconciler reconciler(ProjectFileRepository repo) {
        return new MediaFileTypeReconciler(true, repo);
    }

    @Test
    @DisplayName("存量脏行按文件名扩展名改回来：image→jpg / video→mov / audio→m4a")
    void rewritesMediaTypeRowsToExtension() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        ProjectFile jpg = file(2248L, "现场影像-20260902-191122-D160-d16044f3.jpg", "image");
        ProjectFile mov = file(1752L, "现场影像-20260817-173704-A1A8-a1a83a84.mov", "video");
        ProjectFile m4a = file(2253L, "现场录音-20260903-181645-4D63-4d632ecb.m4a", "audio");
        when(repo.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList()))
                .thenReturn(List.of(jpg, mov, m4a));

        assertEquals(3, reconciler(repo).reconcile());

        assertEquals("jpg", jpg.getFileType());
        assertEquals("mov", mov.getFileType());
        assertEquals("m4a", m4a.getFileType());
        verify(repo, times(3)).save(any(ProjectFile.class));
    }

    @Test
    @DisplayName("名字没有扩展名的行保持原样：那正是视觉判定还认 fileType=image 的唯一一档")
    void leavesExtensionlessNamesAlone() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        ProjectFile noExt = file(900L, "扫描件", "image");
        when(repo.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList()))
                .thenReturn(List.of(noExt));

        assertEquals(0, reconciler(repo).reconcile());
        assertEquals("image", noExt.getFileType());
        verify(repo, never()).save(any(ProjectFile.class));
    }

    @Test
    @DisplayName("非 local-mode（云后端 / 团队服务器）一行都不扫：脏行只可能在桌面端本地库")
    void skipsOutsideLocalMode() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);

        assertEquals(0, new MediaFileTypeReconciler(false, repo).reconcile());
        verify(repo, never()).findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList());
    }

    @Test
    @DisplayName("幂等：改过之后再也匹配不到，重复启动是 0 行")
    void idempotentWhenNothingDirty() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList()))
                .thenReturn(List.of());

        assertEquals(0, reconciler(repo).reconcile());
    }

    @Test
    @DisplayName("对账是顺手活：读文件表炸了也不能拦住启动")
    void survivesRepositoryFailure() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> assertEquals(0, reconciler(repo).reconcile()));
    }

    @Test
    @DisplayName("单行改写失败不能中断整轮对账")
    void oneFailureDoesNotAbortTheSweep() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        ProjectFile bad = file(1L, "a.jpg", "image");
        ProjectFile good = file(2L, "b.png", "image");
        when(repo.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(anyList()))
                .thenReturn(List.of(bad, good));
        when(repo.save(bad)).thenThrow(new RuntimeException("boom"));

        assertEquals(1, reconciler(repo).reconcile());
        assertEquals("png", good.getFileType());
    }
}
