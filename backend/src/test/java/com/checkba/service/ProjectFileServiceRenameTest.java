package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 重命名的同名校验必须按「补完后缀之后的最终名字」来查重：
 * 用户只填主名（"summary"）时后缀会被自动补回（"summary.pdf"），
 * 若查重仍拿裸名字去查，就会放过与既有 "summary.pdf" 的碰撞——
 * 两条同名记录不说，物理路径也由名字派生，会把别人的文件内容覆盖掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceRenameTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock
    private com.checkba.storage.StorageServiceFactory storageServiceFactory;
    @Mock
    private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks
    private ProjectFileService projectFileService;

    private static final long PROJECT_ID = 1L;
    private static final long PARENT_ID = 5L;
    private static final long FILE_A = 100L;

    private ProjectFile fileA() {
        ProjectFile f = new ProjectFile();
        f.setId(FILE_A);
        f.setProjectId(PROJECT_ID);
        f.setParentId(PARENT_ID);
        f.setIsFolder(false);
        f.setName("report.pdf");
        f.setFileType("pdf");
        return f;
    }

    @Test
    void 补后缀后与既有文件同名应当报错() {
        when(projectFileRepository.findById(FILE_A)).thenReturn(Optional.of(fileA()));
        // 同目录下已存在另一份 summary.pdf
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                PROJECT_ID, PARENT_ID, "summary.pdf", FILE_A)).thenReturn(true);
        when(projectFileRepository.save(any(ProjectFile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.rename(FILE_A, "summary", 1L));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("summary"));
    }

    @Test
    void 不与他人碰撞时补后缀重命名仍应成功() {
        when(projectFileRepository.findById(FILE_A)).thenReturn(Optional.of(fileA()));
        when(projectFileRepository.save(any(ProjectFile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFile renamed = projectFileService.rename(FILE_A, "summary", 1L);
        assertEquals("summary.pdf", renamed.getName());
    }
}
