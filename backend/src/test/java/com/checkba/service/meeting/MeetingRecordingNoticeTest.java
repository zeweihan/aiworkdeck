package com.checkba.service.meeting;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台档会议转写的单独告知。
 *
 * <p>会议录音是全产品唯一会完整落到我们磁盘上的用户内容，且录的往往是第三方
 * （客户、对方当事人）的声音——被录的人不是我们的用户，没同意过任何条款。
 * 这里锁住的是四件会在重构里悄悄失守的事：
 * 1. 默认必须是「没确认过」，绝不预设为真；
 * 2. 告知内容改版后旧确认作废（版本机制）；
 * 3. 文本必须说全「传到哪 / 转给谁 / 何时删 / 不想出网怎么办」；
 * 4. 文案不得命中前端的「掉线」判据，也不许有 emoji。
 *
 * <p>与 {@code CrossBorderConsentTest} 是同形态不同强度：那条是《个人信息保护法》
 * 第三十九条「向境外提供」的单独同意，做成了写库前的硬拦截；会议录音走境内听悟 +
 * 境内对象存储，<b>不出境</b>，触发的是告知义务，因此这里刻意没有任何服务端闸——
 * 硬闸的代价是律师录完两小时会、点转写时被模态框拦住。
 */
class MeetingRecordingNoticeTest {

    private SystemSettingService settingsWith(Map<String, String> stored) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any()))
                .thenAnswer(inv -> stored.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        return settings;
    }

    @Test
    @DisplayName("从未确认：默认必须是「没确认过」，绝不预设为真")
    void defaultsToNotAcknowledged() {
        assertFalse(MeetingRecordingNotice.acknowledged(settingsWith(new HashMap<>())));
    }

    @Test
    @DisplayName("已确认且版本一致：不再重复打扰（每机一次，不是每次）")
    void storedAcknowledgementWithMatchingVersionCounts() {
        Map<String, String> stored = new HashMap<>();
        stored.put(MeetingRecordingNotice.KEY_ACKNOWLEDGED_AT, "2026-08-17T10:00:00Z");
        stored.put(MeetingRecordingNotice.KEY_VERSION, MeetingRecordingNotice.VERSION);
        SystemSettingService settings = settingsWith(stored);

        assertTrue(MeetingRecordingNotice.acknowledged(settings));
        assertEquals("2026-08-17T10:00:00Z", MeetingRecordingNotice.acknowledgedAt(settings));
    }

    @Test
    @DisplayName("告知内容改版：旧确认作废，必须重新告知一次")
    void staleVersionInvalidatesAcknowledgement() {
        Map<String, String> stored = new HashMap<>();
        stored.put(MeetingRecordingNotice.KEY_ACKNOWLEDGED_AT, "2026-01-01T10:00:00Z");
        stored.put(MeetingRecordingNotice.KEY_VERSION, "2025-01-01");
        SystemSettingService settings = settingsWith(stored);

        assertFalse(MeetingRecordingNotice.acknowledged(settings),
                "告知内容变了，旧确认覆盖不到新的处理方式");
        assertEquals("", MeetingRecordingNotice.acknowledgedAt(settings),
                "版本过期时不该把旧时间戳当成有效确认摆出来");
    }

    @Test
    @DisplayName("有时间戳但没版本号（脏数据 / 手工改库）按未确认处理")
    void timestampWithoutVersionIsNotAcknowledged() {
        Map<String, String> stored = new HashMap<>();
        stored.put(MeetingRecordingNotice.KEY_ACKNOWLEDGED_AT, "2026-08-17T10:00:00Z");
        assertFalse(MeetingRecordingNotice.acknowledged(settingsWith(stored)));
    }

    @Test
    @DisplayName("撤回写空串而不是留着旧值")
    void withdrawalClearsBothKeys() {
        Map<String, String> off = MeetingRecordingNotice.updates(false);
        assertEquals("", off.get(MeetingRecordingNotice.KEY_ACKNOWLEDGED_AT));
        assertEquals("", off.get(MeetingRecordingNotice.KEY_VERSION));

        Map<String, String> on = MeetingRecordingNotice.updates(true);
        assertEquals(MeetingRecordingNotice.VERSION, on.get(MeetingRecordingNotice.KEY_VERSION));
        assertFalse(on.get(MeetingRecordingNotice.KEY_ACKNOWLEDGED_AT).isBlank());
    }

    @Test
    @DisplayName("告知必须说全：传到哪、转给谁、何时删、不想出网怎么办")
    void noticeCoversTheFourThingsThatMatter() {
        String body = MeetingRecordingNotice.body();
        // 只讲风险不给出路的告知，用户唯一能做的是放弃这个功能——
        // 而本机转写（P3）已经可用，这条出路是真的
        for (String required : List.of("对象存储", "听悟", "删除", "24 小时", "录音不出本机")) {
            assertTrue(body.contains(required), "告知里缺少「" + required + "」：" + body);
        }
    }

    @Test
    @DisplayName("告知文案不得命中前端的「掉线」判据，也不许有 emoji")
    void noticeTextObeysTheHouseRules() {
        String body = MeetingRecordingNotice.body();
        // frontend/src/services/api.js 对含这三个子串的 message 判定未登录并清会话
        for (String forbidden : List.of("登录", "未授权", "请先")) {
            assertFalse(body.contains(forbidden), "告知文案含「" + forbidden + "」：" + body);
        }
        assertFalse(body.codePoints().anyMatch(MeetingRecordingNoticeTest::isEmoji),
                "全站禁 emoji：" + body);
    }

    /** 常见 emoji 区段（含补充符号、杂项符号、旗帜与变体选择符）。 */
    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || cp == 0xFE0F;
    }
}
