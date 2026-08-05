package com.checkba.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 2026-08 审计不变式：免登（local-mode）必须钉死回环，否则拒绝启动。
 */
class LocalModeLoopbackGuardTest {

    @Test
    void localModeRejectsNonLoopbackAddress() {
        assertThrows(IllegalStateException.class, () -> new LocalModeLoopbackGuard(true, "0.0.0.0"));
        assertThrows(IllegalStateException.class, () -> new LocalModeLoopbackGuard(true, "192.168.1.10"));
    }

    @Test
    void localModeRejectsUnsetAddress() {
        // 未设置时 Spring 默认监听 0.0.0.0，同样必须拒绝
        assertThrows(IllegalStateException.class, () -> new LocalModeLoopbackGuard(true, ""));
        assertThrows(IllegalStateException.class, () -> new LocalModeLoopbackGuard(true, null));
    }

    @Test
    void localModeAllowsLoopback() {
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(true, "127.0.0.1"));
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(true, "localhost"));
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(true, " 127.0.0.1 "));
    }

    @Test
    void serverModeUnrestricted() {
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(false, "0.0.0.0"));
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(false, ""));
        assertDoesNotThrow(() -> new LocalModeLoopbackGuard(false, null));
    }
}
