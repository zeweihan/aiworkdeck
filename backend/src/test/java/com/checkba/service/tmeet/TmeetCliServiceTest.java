// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.service.tmeet.dto.TmeetAuthStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TmeetCliServiceTest {

    @Test
    void testResolveCliPath() {
        TmeetCliService service = new TmeetCliService("");
        String path = service.resolveCliPath();
        assertNotNull(path);
        assertFalse(path.isBlank());
    }

    @Test
    void testIsCliAvailable() {
        TmeetCliService service = new TmeetCliService("");
        // 在本地开发机上，tmeet 已经安装在 ~/.local/bin/tmeet
        boolean available = service.isCliAvailable();
        assertTrue(available, "当前开发机上应能探测到 tmeet CLI");
    }

    @Test
    void testGetAuthStatus() {
        TmeetCliService service = new TmeetCliService("");
        TmeetAuthStatus status = service.getAuthStatus();
        assertNotNull(status);
        assertTrue(status.isCliAvailable());
        // 当前本机已完成 tmeet 登录
        assertTrue(status.isLoggedIn());
        assertNotNull(status.getUserName());
        assertNotNull(status.getOpenId());
    }
}
