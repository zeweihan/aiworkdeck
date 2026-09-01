package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.SystemSetting;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.SystemSettingRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 锁定本机身份解析规则（PR-A + 2026-08-05「选错账号」修正）：
 * <ol>
 *   <li>已持久化的选择永远优先，跨重启稳定；</li>
 *   <li>其次 username=local（历史竞态窗口的产物，不能翻转）；</li>
 *   <li>再次按数据量：唯一有数据的候选直接选中；多个有数据 → needsSelection，绝不静默选择；
 *       全空库复用空 admin；</li>
 *   <li>测试账号（qa_bot_* / claude-e2e / e2e_keepalive）不进候选，真实账号一个都不许误排。</li>
 * </ol>
 */
class LocalIdentityServiceTest {

    private UserRepository users;
    private ProjectRepository projects;
    private ProjectFileRepository files;
    private SystemSettingRepository settings;
    private final List<User> table = new ArrayList<>();
    private SystemSetting storedSelection;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        projects = mock(ProjectRepository.class);
        files = mock(ProjectFileRepository.class);
        settings = mock(SystemSettingRepository.class);
        table.clear();
        storedSelection = null;

        when(users.findAll()).thenAnswer(inv -> new ArrayList<>(table));
        when(users.findByUsername(anyString())).thenAnswer(inv -> table.stream()
                .filter(u -> inv.getArgument(0).equals(u.getUsername())).findFirst());
        when(users.findById(anyLong())).thenAnswer(inv -> table.stream()
                .filter(u -> inv.getArgument(0).equals(u.getId())).findFirst());
        when(users.existsById(anyLong())).thenAnswer(inv -> table.stream()
                .anyMatch(u -> inv.getArgument(0).equals(u.getId())));
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(99L);
                table.add(u);
            }
            return u;
        });
        // 默认零数据；需要数据的用例逐个覆盖
        when(projects.countByUserId(anyLong())).thenReturn(0L);
        when(files.countByUserIdAndIsDeletedFalse(anyLong())).thenReturn(0L);

        when(settings.findByKey(LocalIdentityService.SELECTED_KEY))
                .thenAnswer(inv -> Optional.ofNullable(storedSelection));
        when(settings.save(any(SystemSetting.class))).thenAnswer(inv -> {
            storedSelection = inv.getArgument(0);
            return storedSelection;
        });
    }

    @AfterEach
    void resetStatic() {
        // 构造 localMode=true 实例会静态注册到 AuthController，必须清理防止泄漏到其他测试
        AuthController.registerLocalIdentityService(null);
    }

    private User user(Long id, String username, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        table.add(u);
        return u;
    }

    private void data(Long userId, long projectCount, long fileCount) {
        when(projects.countByUserId(userId)).thenReturn(projectCount);
        when(files.countByUserIdAndIsDeletedFalse(userId)).thenReturn(fileCount);
    }

    private LocalIdentityService service(boolean localMode) {
        return new LocalIdentityService(users, projects, files, settings, localMode);
    }

    private void persistSelection(long userId) {
        SystemSetting s = new SystemSetting();
        s.setKey(LocalIdentityService.SELECTED_KEY);
        s.setValue(String.valueOf(userId));
        storedSelection = s;
    }

    // ==================== 规则 1：已持久化的选择 ====================

    @Test
    void persistedSelectionWinsOverEverything() {
        user(1L, "admin", "管理员");
        User picked = user(2L, "hanzewei", "韩泽伟");
        user(3L, "local", LocalIdentityService.LOCAL_DISPLAY_NAME);
        data(2L, 6, 21);
        persistSelection(picked.getId());

        LocalIdentityService svc = service(false);
        assertEquals(2L, svc.localUserId(), "已选定的身份必须优先于 local 用户与数据量判定");
        assertFalse(svc.needsSelection());
        verify(users, never()).findByUsername("local");
    }

    @Test
    void persistedSelectionPointingAtMissingUserIsReResolved() {
        // 库被替换/回滚：指针悬空时必须回落到重新解析，而不是抱着一个不存在的 userId
        User admin = user(1L, "admin", "管理员");
        persistSelection(777L);

        LocalIdentityService svc = service(false);
        assertEquals(admin.getId(), svc.localUserId());
        assertFalse(svc.needsSelection());
        assertEquals("1", storedSelection.getValue(), "重新解析的结果要覆盖悬空指针");
    }

    // ==================== 规则 2：username=local ====================

    @Test
    void existingLocalUserWinsOverAdminAndIsPersisted() {
        // 竞态窗口内创建过 local 用户后，即使 admin 后来出现，解析结果也不能翻转
        user(1L, "admin", "管理员");
        user(3L, "local", LocalIdentityService.LOCAL_DISPLAY_NAME);
        data(1L, 5, 10); // admin 就算有数据也不夺权

        LocalIdentityService svc = service(false);
        assertEquals(3L, svc.localUserId(), "local 用户存在时必须优先，跨重启不能换 userId");
        assertFalse(svc.needsSelection());
        assertEquals("3", storedSelection.getValue(), "命中后要固化成持久化选择");
    }

    // ==================== 规则 3：数据量判定 ====================

    @Test
    void singleCandidateWithDataIsSelectedAndPersisted() {
        // 老安装的典型形态：admin 是空壳，数据全在真实账号名下
        user(1L, "admin", "管理员");
        user(2L, "hanzewei", "韩泽伟");
        user(4L, "newuser", "新用户");
        data(2L, 6, 21);

        LocalIdentityService svc = service(false);
        assertEquals(2L, svc.localUserId(), "唯一有数据的账号就是本机工作区");
        assertFalse(svc.needsSelection());
        assertEquals("2", storedSelection.getValue());
    }

    @Test
    void multipleCandidatesWithDataNeedSelectionAndAreNotPersisted() {
        user(1L, "admin", "管理员");
        user(2L, "hanzewei", "韩泽伟");
        data(1L, 1, 0);
        data(2L, 6, 21);

        LocalIdentityService svc = service(false);
        assertTrue(svc.needsSelection(), "多个候选有数据时必须交给用户选，绝不静默挑一个");
        assertEquals(2L, svc.localUserId(), "待选定期间临时落在数据量最大的候选，避免全站 500");
        assertNull(storedSelection, "待选定不得写持久化——用户的选择才算数");
        verify(users, never()).save(any(User.class));
    }

    @Test
    void emptyDatabaseReusesExistingAdminAndRenamesDisplayName() {
        User admin = user(1L, "admin", "管理员");

        LocalIdentityService svc = service(false);
        assertEquals(1L, svc.localUserId(), "全空库复用 DataInitializer 播的 admin，不新增账号");
        assertFalse(svc.needsSelection());
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, admin.getDisplayName());
        assertEquals("1", storedSelection.getValue());
    }

    @Test
    void createsLocalUserWhenNoUsersAtAll() {
        LocalIdentityService svc = service(false);
        assertEquals(99L, svc.localUserId());
        assertFalse(svc.needsSelection());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User created = captor.getValue();
        assertEquals("local", created.getUsername());
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, created.getDisplayName());
        assertNotNull(created.getPassword(), "密码列非空约束需要满足");
        assertFalse(created.getPassword().isBlank());
        assertEquals("99", storedSelection.getValue());
    }

    @Test
    void realAccountDisplayNameIsNeverRewritten() {
        // 改名只针对系统默认的 admin；用户自己的账号名一个字都不能动
        User real = user(2L, "hanzewei", "韩泽伟");
        data(2L, 6, 21);

        service(false).localUserId();
        assertEquals("韩泽伟", real.getDisplayName());
    }

    // ==================== 测试账号排除 ====================

    @Test
    void testAccountsAreExcludedButRealAccountsAreNot() {
        user(1L, "admin", "管理员");
        user(2L, "hanzewei", "韩泽伟");
        user(3L, "hanzewei1", "韩泽伟1");
        user(4L, "newuser", "新用户");
        user(5L, "qa_bot_1754300000", "QA");
        user(6L, "claude-e2e", "E2E");
        user(7L, "e2e_keepalive", "保活");
        data(2L, 6, 21);
        data(5L, 3, 9);
        data(6L, 2, 4);
        data(7L, 1, 1);

        LocalIdentityService svc = service(false);
        List<String> names = svc.candidates().stream()
                .map(LocalIdentityService.Candidate::username).toList();
        assertEquals(List.of("hanzewei", "admin", "hanzewei1", "newuser"), names,
                "测试账号出局；名字像但不是测试脚手架造的（hanzewei1 / newuser）必须留在候选里");
        assertFalse(svc.needsSelection(), "排除测试账号后只剩 hanzewei 有数据，可以直接选定");
        assertEquals(2L, svc.localUserId());
    }

    @Test
    void testAccountPrefixMatchIsCaseInsensitiveAndPrefixOnly() {
        assertTrue(LocalIdentityService.isTestAccount("qa_bot_123"));
        assertTrue(LocalIdentityService.isTestAccount("QA_BOT_123"));
        assertTrue(LocalIdentityService.isTestAccount("claude-e2e-2"));
        assertTrue(LocalIdentityService.isTestAccount("e2e_keepalive"));
        // 只按前缀匹配，不做子串匹配——真实用户名里出现这些词不该被吃掉
        assertFalse(LocalIdentityService.isTestAccount("qa_bot"), "少了下划线就不是脚手架格式");
        assertFalse(LocalIdentityService.isTestAccount("my_qa_bot_helper"));
        assertFalse(LocalIdentityService.isTestAccount("hanzewei"));
        assertFalse(LocalIdentityService.isTestAccount(null));
    }

    // ==================== select ====================

    @Test
    void selectPersistsAndClearsPendingState() {
        user(1L, "admin", "管理员");
        user(2L, "hanzewei", "韩泽伟");
        data(1L, 1, 0);
        data(2L, 6, 21);

        LocalIdentityService svc = service(false);
        assertTrue(svc.needsSelection());

        svc.select(1L);
        assertFalse(svc.needsSelection(), "选定后缓存要失效并回到已确定状态");
        assertEquals(1L, svc.localUserId());
        assertEquals("1", storedSelection.getValue());
    }

    @Test
    void selectRejectsUnknownNullAndTestAccounts() {
        user(2L, "hanzewei", "韩泽伟");
        user(5L, "qa_bot_1754300000", "QA");
        data(2L, 6, 21);

        LocalIdentityService svc = service(false);
        assertThrows(IllegalArgumentException.class, () -> svc.select(null));
        assertThrows(IllegalArgumentException.class, () -> svc.select(404L));
        assertThrows(IllegalArgumentException.class, () -> svc.select(5L),
                "测试账号不在候选集内，不能被选为本机工作区");
    }

    // ==================== 缓存与 AuthController 接线 ====================

    @Test
    void cachesResolvedId() {
        user(1L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME);

        LocalIdentityService svc = service(false);
        assertEquals(1L, svc.localUserId());
        assertEquals(1L, svc.localUserId());
        // 只解析一次：候选统计是每用户 2 条 count 查询，热路径上不能每请求重跑
        verify(users, times(1)).findByUsername("local");
        verify(users, times(1)).findAll();
    }

    @Test
    void localModeHijacksSessionResolutionRegardlessOfHeader() {
        user(1L, "admin", LocalIdentityService.LOCAL_DISPLAY_NAME);

        service(true); // localMode=true 构造即静态注册
        assertEquals(1L, AuthController.getUserIdFromSession(null), "无 header 也应解析为本机用户");
        assertEquals(1L, AuthController.getUserIdFromSession("session_garbage"),
                "任意无效 session 也应解析为本机用户");
    }

    @Test
    void pendingSelectionStillResolvesSessionsInsteadOf500() {
        // 待选定不能让全站 500：getUserIdFromSession 仍然拿得到一个 userId
        user(1L, "admin", "管理员");
        user(2L, "hanzewei", "韩泽伟");
        data(1L, 1, 0);
        data(2L, 6, 21);

        service(true);
        assertEquals(2L, AuthController.getUserIdFromSession(null));
    }

    @Test
    void serverModeDoesNotRegister() {
        service(false);
        // 非 local-mode 行为一字不变：无效 session 仍然是未登录
        assertNull(AuthController.getUserIdFromSession("session_garbage"));
        assertNull(AuthController.getUserIdFromSession(null));
    }

    // ==================== displayNameOf：读取/输出时本地化哨兵值 ====================
    // v0.30.0 发版前修复：LOCAL_DISPLAY_NAME 这个中文常量被写进数据库当 displayName，
    // 界面语言切到英文后所有读它的响应都要经这个 helper 本地化，不改库里存的值。

    @AfterEach
    void resetLangText() {
        LangText.reset();
    }

    @Test
    void displayNameOf_sentinelInChinese_returnsChinese() {
        // 未登记 AppLanguageService 时 LangText 回退中文，与「未初始化=中文」的既有默认态一致
        LangText.reset();
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME,
                LocalIdentityService.displayNameOf(LocalIdentityService.LOCAL_DISPLAY_NAME));
    }

    @Test
    void displayNameOf_sentinelInEnglish_returnsEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
        assertEquals("Local user",
                LocalIdentityService.displayNameOf(LocalIdentityService.LOCAL_DISPLAY_NAME));
    }

    @Test
    void displayNameOf_realUserName_passesThroughUnchanged() {
        // 云端多用户场景：真实用户的 displayName 一个字都不能动，哪怕界面语言是英文
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
        assertEquals("韩泽伟", LocalIdentityService.displayNameOf("韩泽伟"));
    }

    @Test
    void displayNameOf_null_passesThroughUnchanged() {
        assertNull(LocalIdentityService.displayNameOf(null));
    }
}
