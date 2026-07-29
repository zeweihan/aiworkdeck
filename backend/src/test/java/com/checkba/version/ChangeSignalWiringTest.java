package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// desktop profile = 嵌入式 H2（与 IdorAuthIntegrationTest / DesktopContextSmokeTest 同一约定）。
// 默认 profile 连 localhost:5432 的 PostgreSQL，本机恰好有库时测试是绿的，CI 上必挂——
// 全上下文测试一律显式走 desktop profile，不得依赖开发机的外部服务。
@SpringBootTest
@ActiveProfiles("desktop")
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
