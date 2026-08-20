package com.checkba.service;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手机号被转移到归一账号之后，原持有者的登录会话必须一并作废。
 *
 * <p>病灶（dev-board#75「手机端一个项目都读不到」的真因）：
 * <ol>
 *   <li>用户先在手机上用手机号短信登录云后端 → 云端建了账号 A，手机 App 存下 A 的会话；</li>
 *   <li>之后桌面端用 awdk_ 桥接 → 云端解析出账号 B（官网账户），
 *       {@code claimPhoneFromWebsite} 把手机号从 A <b>转移</b>到 B（A.phone 置空）；</li>
 *   <li>桌面端把项目目录镜像推到云端，镜像挂在 <b>B</b> 名下；</li>
 *   <li>可手机 App 手里那张 <b>A 的会话仍然有效</b>（转移不动会话），
 *       它继续以 A 的身份请求 {@code /api/mobile/projects}。</li>
 * </ol>
 *
 * <p>A 名下什么都没有，于是返回一个**合法的空数组**——没有报错、没有 4010、没有任何提示。
 * 用户看到的就是「一个项目都读不到」，而且反复重进也一样：会话不到期就永远不会自愈。
 *
 * <p>作废之后手机端被迫重新走短信登录，验证码会把它落到归一后的账号 B 上。
 * 安全上没有放松：占用方本来就是用同一个号码验证过控制权的账号，是同一个人。
 */
class PhoneClaimSessionRevocationTest {

    private static final String PHONE = "18600001590";

    private UserRepository userRepository;
    private UserSessionService sessionService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionService = mock(UserSessionService.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        userService = new UserService(userRepository, sessionService);
    }

    private static User user(long id, String phone) {
        User u = new User();
        u.setId(id);
        u.setPhone(phone);
        return u;
    }

    @Test
    @DisplayName("号码从旧账号转移到桥接账号：旧账号的会话必须作废，否则手机端永远停在空列表")
    void transferRevokesPreviousHolderSessions() {
        User oldHolder = user(7L, PHONE);
        User bridged = user(3L, null);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(oldHolder));

        userService.claimPhoneFromWebsite(bridged, PHONE);

        assertNull(oldHolder.getPhone(), "号码要从旧账号摘掉");
        assertEquals(PHONE, bridged.getPhone(), "号码要落到桥接账号上");
        verify(sessionService).revokeAllForUser(7L);
    }

    @Test
    @DisplayName("没有旧持有者时不作废任何人的会话")
    void noHolderMeansNoRevocation() {
        User bridged = user(3L, null);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

        userService.claimPhoneFromWebsite(bridged, PHONE);

        assertEquals(PHONE, bridged.getPhone());
        verify(sessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("号码本来就在桥接账号上：什么都不做，绝不把自己的会话踢掉")
    void selfHeldPhoneIsANoOp() {
        User bridged = user(3L, PHONE);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(bridged));

        userService.claimPhoneFromWebsite(bridged, PHONE);

        assertEquals(PHONE, bridged.getPhone());
        verify(sessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("桥接账号已绑另一个号码：既有「不覆盖」取舍不变，也不动任何会话")
    void existingDifferentPhoneIsNotOverwritten() {
        User bridged = user(3L, "13900000000");

        userService.claimPhoneFromWebsite(bridged, PHONE);

        assertEquals("13900000000", bridged.getPhone(), "不许悄悄改掉一个已在工作的登录入口");
        verify(sessionService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("作废会话抛异常不影响认领本身——认领是桥接的顺手动作，永不抛出")
    void revocationFailureNeverBreaksTheClaim() {
        User oldHolder = user(7L, PHONE);
        User bridged = user(3L, null);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(oldHolder));
        when(sessionService.revokeAllForUser(anyLong())).thenThrow(new IllegalStateException("db down"));

        userService.claimPhoneFromWebsite(bridged, PHONE);

        assertEquals(PHONE, bridged.getPhone(), "号码仍要认领成功");
    }

    @Test
    @DisplayName("非法号码直接忽略，不触发任何写入")
    void malformedPhoneIsIgnored() {
        User bridged = user(3L, null);

        userService.claimPhoneFromWebsite(bridged, "not-a-phone");
        userService.claimPhoneFromWebsite(bridged, null);

        assertNull(bridged.getPhone());
        verify(userRepository, never()).findByPhone(anyString());
        verify(sessionService, never()).revokeAllForUser(anyLong());
    }
}
