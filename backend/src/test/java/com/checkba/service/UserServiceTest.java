package com.checkba.service;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 锁定口令加密与登录兼容行为：
 * 注册存 BCrypt 而非明文；历史明文口令登录成功后自动升级；错误口令拒绝。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void registerStoresBcryptNotPlaintext() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User u = userService.register("alice", "secret123", "Alice");

        assertNotEquals("secret123", u.getPassword());
        assertTrue(u.getPassword().startsWith("$2"), "password should be a BCrypt hash");
    }

    @Test
    void loginSucceedsForBcryptUser() {
        User stored = new User();
        stored.setUsername("bob");
        stored.setPassword(UserService.encodePassword("pw123456"));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(stored));

        User u = userService.login("bob", "pw123456");

        assertEquals("bob", u.getUsername());
    }

    @Test
    void loginUpgradesLegacyPlaintextInPlace() {
        User legacy = new User();
        legacy.setUsername("carol");
        legacy.setPassword("oldplain"); // 历史明文
        when(userRepository.findByUsername("carol")).thenReturn(Optional.of(legacy));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.login("carol", "oldplain");

        assertTrue(legacy.getPassword().startsWith("$2"), "legacy plaintext should be upgraded to BCrypt");
        verify(userRepository).save(legacy);
    }

    @Test
    void loginRejectsWrongPassword() {
        User stored = new User();
        stored.setUsername("dave");
        stored.setPassword(UserService.encodePassword("right"));
        when(userRepository.findByUsername("dave")).thenReturn(Optional.of(stored));

        assertThrows(IllegalArgumentException.class, () -> userService.login("dave", "wrong"));
    }
}
