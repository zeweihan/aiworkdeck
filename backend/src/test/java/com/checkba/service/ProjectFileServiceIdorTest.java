package com.checkba.service;

import com.checkba.model.dto.ProjectFileBatchRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 锁定 ProjectFileService 批量操作的越权(IDOR)防护：
 * batchDelete/batchMove 委托的 delete/move 不校验 projectId，批量入口须自行拒绝跨项目文件。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileServiceIdorTest {

    @Mock
    private ProjectFileRepository projectFileRepository;

    /** 额度检查在 batchMove 里先于逐个归属校验执行，不给它一个实例会 NPE 掩盖真正的断言。 */
    @Mock
    private com.checkba.service.quota.StageQuotaService stageQuotaService;

    @InjectMocks
    private ProjectFileService projectFileService;

    @Test
    void batchDeleteRejectsCrossProjectFile() {
        ProjectFile foreign = new ProjectFile();
        foreign.setId(77L);
        foreign.setProjectId(999L);
        when(projectFileRepository.findById(77L)).thenReturn(Optional.of(foreign));

        ProjectFileBatchRequest req = new ProjectFileBatchRequest();
        req.setFileIds(List.of(77L));

        assertThrows(IllegalArgumentException.class,
                () -> projectFileService.batchDelete(1L, req, 1L));
    }

    @Test
    void batchMoveRejectsCrossProjectFile() {
        ProjectFile foreign = new ProjectFile();
        foreign.setId(88L);
        foreign.setProjectId(999L);
        when(projectFileRepository.findById(88L)).thenReturn(Optional.of(foreign));

        ProjectFileBatchRequest req = new ProjectFileBatchRequest();
        req.setFileIds(List.of(88L));
        req.setTargetParentId(5L);

        assertThrows(IllegalArgumentException.class,
                () -> projectFileService.batchMove(1L, req, 1L));
    }

    /**
     * 缓存区免费额度此前只挂在 batchMove 上，单文件 move 这条同样能移进缓存区的路径
     * 一个字节都不查——免费用户一次拖一个就能无限往里塞，付费闸形同虚设。
     */
    @Test
    void singleMoveIntoStagingIsQuotaChecked() {
        ProjectFile own = new ProjectFile();
        own.setId(42L);
        own.setProjectId(1L);
        own.setIsFolder(false);
        own.setName("a.docx");
        when(projectFileRepository.findById(42L)).thenReturn(Optional.of(own));
        doThrow(new com.checkba.exception.StageQuotaExceededException("超额", 20, 0, 20, 1L))
                .when(stageQuotaService).checkAdmission(eq(9L), anyList());

        assertThrows(com.checkba.exception.StageQuotaExceededException.class,
                () -> projectFileService.move(42L, 9L, null, 1L));
    }
}
