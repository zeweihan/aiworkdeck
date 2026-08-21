package com.checkba.service.mobile;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.UserSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 孤儿手机账号会话对账（dev-board#95）。
 *
 * <p>现场：桌面端每几分钟把 10 个项目推到 user 3 名下，手机端手里那张会话却属于
 * user 4——一个 displayName 是掩码手机号、phone 已被转走的孤儿号。
 * {@code /api/mobile/projects} 按 userId 取目录，于是返回一个<b>合法的空数组</b>：
 * 不报错、不提示，手机端就是「一个项目都读不到」，重进多少次都一样。
 * 认领时作废会话那条（dev-board#75）只在认领那一刻生效，救不了在它之前就被拆开的账号。
 */
class OrphanPhoneSessionReconcilerTest {

    private User user(Long id, String username, String displayName, String phone) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPhone(phone);
        return u;
    }

    private OrphanPhoneSessionReconciler reconciler(UserRepository repo, UserSessionService sessions) {
        return new OrphanPhoneSessionReconciler(repo, sessions);
    }

    @Test
    @DisplayName("号码被转走的孤儿号：作废它的遗留会话，手机端才会被迫重登到归一后的账号")
    void revokesSessionsOfOrphanPhoneAccount() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        // 现场那一对：user 3 拿着号码，user 4 是被转走号码后的孤儿
        when(repo.findAll()).thenReturn(List.of(
                user(3L, "awd_hanzewei", "韩泽伟", "18610211590"),
                user(4L, "uspe8yzah", "186****1590", null)));
        when(sessions.revokeAllForUser(4L)).thenReturn(3L);

        long revoked = reconciler(repo, sessions).reconcile();

        assertEquals(3L, revoked);
        verify(sessions).revokeAllForUser(4L);
        verify(sessions, never()).revokeAllForUser(3L);
    }

    @Test
    @DisplayName("号码还在的短信登录账号绝不能碰——那是正常账号，作废会把人踢下线")
    void leavesLivePhoneAccountAlone() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenReturn(List.of(
                user(7L, "abc123xyz", "138****4321", "13800014321")));

        assertEquals(0L, reconciler(repo, sessions).reconcile());
        verify(sessions, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("admin 与官网桥接账号本来就没绑号，不能因为 phone 为空就被当成孤儿")
    void leavesAccountsThatNeverHadAPhoneAlone() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenReturn(List.of(
                user(1L, "admin", "管理员", null),
                user(3L, "awd_hanzewei", "韩泽伟", null)));

        assertEquals(0L, reconciler(repo, sessions).reconcile());
        verify(sessions, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("掩码形状要卡准：像手机号但不是 maskPhone 产物的 displayName 不算数")
    void onlyMatchesTheMaskPhoneShape() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenReturn(List.of(
                user(11L, "a", "18610211590", null),      // 没打码的完整号码
                user(12L, "b", "186***1590", null),       // 星号数量不对
                user(13L, "c", "286****1590", null),      // 不是 1 开头
                user(14L, "d", "186****1590 (旧)", null)));// 有后缀

        assertEquals(0L, reconciler(repo, sessions).reconcile());
        verify(sessions, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("对账是顺手活：读用户表炸了也不能拦住启动")
    void surviveRepositoryFailure() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> assertEquals(0L, reconciler(repo, sessions).reconcile()));
    }

    @Test
    @DisplayName("单个账号作废失败不能中断整轮对账")
    void oneFailureDoesNotAbortTheSweep() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenReturn(List.of(
                user(4L, "a", "186****1590", null),
                user(5L, "b", "139****8888", null)));
        when(sessions.revokeAllForUser(4L)).thenThrow(new RuntimeException("boom"));
        when(sessions.revokeAllForUser(5L)).thenReturn(2L);

        assertEquals(2L, reconciler(repo, sessions).reconcile());
        verify(sessions).revokeAllForUser(5L);
    }

    @Test
    @DisplayName("幂等：没有遗留会话时是 0 条，重复启动不产生副作用")
    void idempotentWhenNothingToRevoke() {
        UserRepository repo = mock(UserRepository.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(repo.findAll()).thenReturn(List.of(user(4L, "a", "186****1590", null)));
        when(sessions.revokeAllForUser(4L)).thenReturn(0L);

        assertEquals(0L, reconciler(repo, sessions).reconcile());
    }
}
