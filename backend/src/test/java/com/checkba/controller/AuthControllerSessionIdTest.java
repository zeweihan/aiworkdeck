package com.checkba.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定会话 ID 的不可预测性。
 *
 * 历史实现是 "session_" + currentTimeMillis() + Math.random() 的 13 位小数：
 * Math.random() 背后是 48 位 LCG，攻击者注册一个账号拿到自己的会话 ID 就能反解种子，
 * 进而推算其他人的会话 ID（时间戳部分本就可枚举），等于全站账号接管。
 * 这里锁住两点：不含可枚举的时间戳、熵足够且不重复。
 */
class AuthControllerSessionIdTest {

    private static String generate(AuthController controller) throws Exception {
        Method m = AuthController.class.getDeclaredMethod("generateSessionId");
        m.setAccessible(true);
        return (String) m.invoke(controller);
    }

    @Test
    void sessionIdIsUnpredictable() throws Exception {
        AuthController controller = new AuthController(null, null, null, null, null, null);

        long before = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(generate(controller));
        }
        long after = System.currentTimeMillis();

        // 500 次全不重复
        assertEquals(500, seen.size(), "会话 ID 出现重复");

        for (String id : seen) {
            assertTrue(id.startsWith("session_"), "会话 ID 前缀应保持不变：" + id);
            String body = id.substring("session_".length());

            // 熵：32 字节 base64url 无填充 = 43 字符
            assertEquals(43, body.length(), "会话 ID 随机部分长度不足：" + id);

            // 不得包含生成时刻的毫秒时间戳——那是可枚举的
            for (long t = before; t <= after; t++) {
                assertFalse(id.contains(String.valueOf(t)),
                        "会话 ID 里嵌了可枚举的时间戳：" + id);
            }
        }
    }
}
