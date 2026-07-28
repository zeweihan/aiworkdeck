package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ChangeSignalWiringTest {

    @Autowired
    private ProjectFileService projectFileService;

    @MockBean
    private WorkSessionService workSessionService;

    @Test
    void creatingAFolderEmitsAChangeSignal() {
        ProjectFile folder = projectFileService.createFolder(7L, null, "新建文件夹", 1L);
        verify(workSessionService, atLeastOnce())
                .onChangeSignal(eq(7L), any(), any());
        projectFileService.permDelete(folder.getId(), 1L);
    }
}
