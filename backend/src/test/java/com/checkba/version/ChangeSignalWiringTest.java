package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.service.ProjectFileService;
import com.checkba.service.UserService;
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
// security.local-mode=false：保持本测试写作时的登录会话语义，并避免 local-mode 的
// LocalIdentityService 静态注册泄漏到同 JVM 后续测试（desktop profile 现默认开启 local-mode）。
@SpringBootTest(properties = {"security.local-mode=false"})
@ActiveProfiles("desktop")
class ChangeSignalWiringTest {

    @Autowired
    private ProjectFileService projectFileService;

    @MockBean
    private WorkSessionService workSessionService;

    @MockBean
    private UserService userService;

    @Test
    void creatingAFolderEmitsAChangeSignal() {
        ProjectFile folder = projectFileService.createFolder(7L, null, "新建文件夹", 1L);
        verify(workSessionService, atLeastOnce())
                .onChangeSignal(eq(7L), any(), any());
        projectFileService.permDelete(folder.getId(), 1L);
    }

    /**
     * 回归测试：resolveUserName 曾经是 "user-" + userId 占位符，时间线上展开工作段，
     * 自动存档显示的是「user-3 · 7 月 28 日」而不是真名。修复后应查真实用户名。
     */
    @Test
    void changeSignalCarriesRealUserNameInsteadOfPlaceholder() {
        User user = new User();
        user.setId(1L);
        user.setUsername("韩泽伟");
        when(userService.getUserById(1L)).thenReturn(user);

        ProjectFile folder = projectFileService.createFolder(7L, null, "新建文件夹二", 1L);

        verify(workSessionService, atLeastOnce())
                .onChangeSignal(eq(7L), eq(1L), eq("韩泽伟"));
        projectFileService.permDelete(folder.getId(), 1L);
    }

    /** 查不到用户（或查询异常）时必须退回兜底名「用户」，不能抛异常阻断文件操作。 */
    @Test
    void changeSignalFallsBackToGenericNameWhenUserLookupFails() {
        when(userService.getUserById(anyLong())).thenThrow(new RuntimeException("查询失败"));

        ProjectFile folder = projectFileService.createFolder(7L, null, "新建文件夹三", 1L);

        verify(workSessionService, atLeastOnce())
                .onChangeSignal(eq(7L), eq(1L), eq("用户"));
        projectFileService.permDelete(folder.getId(), 1L);
    }
}
