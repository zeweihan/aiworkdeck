// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.team;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目名上云前的一次性确认（C4，v0.44.1 真机实测：团队看板里出现了用户其他客户的真实项目名）。
 *
 * <p>锁住四件会在重构里悄悄失守的事，与 {@code MeetingRecordingNoticeTest} 同形态：
 * 1. 默认必须是「没决定过」，绝不预设为已同意（预先勾选的同意在个保法下无效）；
 * 2. 告知改版后旧决定作废（版本机制）；
 * 3. 正文必须说全「上传什么 / 出现在哪 / 谁看得到 / 不同意会怎样」；
 * 4. 文案不得命中前端的「掉线」判据，也不许有 emoji。
 */
class TeamProjectNameNoticeTest {

    private TeamProjectNameNotice noticeWith(Map<String, String> stored) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any()))
                .thenAnswer(inv -> stored.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        org.mockito.Mockito.doAnswer(inv -> stored.put(inv.getArgument(0), inv.getArgument(1)))
                .when(settings).set(anyString(), anyString());
        return new TeamProjectNameNotice(settings);
    }

    @Test
    @DisplayName("全新安装：默认「没决定过」，既不算同意也不算拒绝")
    void defaultsToUndecided() {
        TeamProjectNameNotice notice = noticeWith(new HashMap<>());

        assertFalse(notice.decided());
        assertFalse(notice.granted());
        assertFalse(notice.declined());
    }

    @Test
    @DisplayName("同意与拒绝都记下来：拒绝也是一个决定，记了才不会每天再问一遍")
    void bothDecisionsArePersisted() {
        Map<String, String> stored = new HashMap<>();
        TeamProjectNameNotice notice = noticeWith(stored);

        notice.decide(true);
        assertTrue(notice.decided());
        assertTrue(notice.granted());
        assertFalse(notice.declined());

        notice.decide(false);
        assertTrue(notice.decided());
        assertFalse(notice.granted());
        assertTrue(notice.declined());
    }

    @Test
    @DisplayName("告知改版：旧决定作废，必须重新问一次（旧同意覆盖不到新的可见范围）")
    void staleVersionInvalidatesTheDecision() {
        Map<String, String> stored = new HashMap<>();
        stored.put(TeamProjectNameNotice.KEY_DECISION, TeamProjectNameNotice.GRANTED);
        stored.put(TeamProjectNameNotice.KEY_VERSION, "2020-01-01");

        TeamProjectNameNotice notice = noticeWith(stored);

        assertFalse(notice.decided());
        assertFalse(notice.granted());
    }

    @Test
    @DisplayName("reset 回到「没决定过」：换账户 / 退出团队后听众变了，必须重新问")
    void resetClearsTheDecision() {
        Map<String, String> stored = new HashMap<>();
        TeamProjectNameNotice notice = noticeWith(stored);
        notice.decide(true);

        notice.reset();

        assertFalse(notice.decided());
        assertFalse(notice.granted());
    }

    @Test
    @DisplayName("正文说全四件事：上传什么 / 出现在哪 / 谁看得到 / 不同意会怎样")
    void bodyCoversTheFourFacts() {
        String body = noticeWith(new HashMap<>()).body();

        for (String needle : new String[]{"项目名", "团队看板", "管理者", "匿名编号"}) {
            assertTrue(body.contains(needle), "告知正文缺「" + needle + "」：" + body);
        }
    }

    @Test
    @DisplayName("文案红线：不含三个掉线子串，也不含 emoji")
    void bodyAvoidsLogoutSubstringsAndEmoji() {
        String body = noticeWith(new HashMap<>()).body();

        for (String needle : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(body.contains(needle), "告知正文含掉线子串「" + needle + "」");
        }
        // 与 frontend/tests/team/team-locale.test.mjs 同一张表（CJK 在 0x4E00 以上，不在其中）
        assertFalse(java.util.regex.Pattern
                        .compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")
                        .matcher(body).find(),
                "告知正文不许有 emoji：" + body);
    }
}
