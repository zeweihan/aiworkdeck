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
import static org.mockito.Mockito.when;

/**
 * 锁定 ProjectFileService 批量操作的越权(IDOR)防护：
 * batchDelete/batchMove 委托的 delete/move 不校验 projectId，批量入口须自行拒绝跨项目文件。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileServiceIdorTest {

    @Mock
    private ProjectFileRepository projectFileRepository;

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
}
