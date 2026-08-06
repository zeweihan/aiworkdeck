package com.checkba.service.ai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * conversationId 服务端签发登记簿：格式、归属登记、TTL 清理与强制开关语义。
 * 背景见 ConversationIssuanceService 类注释（2026-08 安全审计遗留项）。
 */
class ConversationIssuanceServiceTest {

    /** 可手动拨动的时钟，用于 TTL 测试。 */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void 签发的ID符合格式且完成归属登记() {
        ConversationIssuanceService service = new ConversationIssuanceService(false, false);
        String id = service.issue(7L, 42L);

        assertTrue(id.matches("conv-\\d+-[A-Za-z0-9_-]{16}"),
                "格式必须为 conv-<毫秒>-<16位随机base64url>，实际：" + id);
        assertTrue(service.isRegistered(id));
        assertEquals(7L, service.ownerOf(id));
    }

    @Test
    void 两次签发的ID互不相同() {
        ConversationIssuanceService service = new ConversationIssuanceService(false, false);
        assertNotEquals(service.issue(7L, 42L), service.issue(7L, 42L));
    }

    @Test
    void 未登记的ID查不到归属() {
        ConversationIssuanceService service = new ConversationIssuanceService(false, false);
        assertNull(service.ownerOf("conv-1754400000000"));
        assertFalse(service.isRegistered("conv-1754400000000"));
        assertNull(service.ownerOf(null));
    }

    @Test
    void 无归属用户不允许签发() {
        ConversationIssuanceService service = new ConversationIssuanceService(false, false);
        assertThrows(IllegalArgumentException.class, () -> service.issue(null, 42L));
    }

    @Test
    void 登记项24小时后惰性过期() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        ConversationIssuanceService service = new ConversationIssuanceService(false, false, clock);
        String id = service.issue(7L, 42L);

        clock.advanceMillis(ConversationIssuanceService.TTL_MILLIS);
        assertEquals(7L, service.ownerOf(id), "恰到期限仍有效");

        clock.advanceMillis(1);
        assertNull(service.ownerOf(id), "超过 24 小时后登记失效");
        assertFalse(service.isRegistered(id));
        assertEquals(0, service.registrationCount(), "惰性过期应把条目摘掉");
    }

    @Test
    void 签发时顺手清理过期条目_防Map无限涨() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        ConversationIssuanceService service = new ConversationIssuanceService(false, false, clock);
        service.issue(7L, 42L);
        service.issue(8L, 42L);

        clock.advanceMillis(ConversationIssuanceService.TTL_MILLIS + 1);
        String fresh = service.issue(9L, 42L);

        assertEquals(1, service.registrationCount(), "过期条目应在签发时被清走");
        assertEquals(9L, service.ownerOf(fresh));
    }

    @Test
    void 强制开关语义_localMode恒不强制() {
        assertFalse(new ConversationIssuanceService(false, false).enforceIssuance(), "默认不强制");
        assertTrue(new ConversationIssuanceService(true, false).enforceIssuance(), "官方云配强制");
        assertFalse(new ConversationIssuanceService(true, true).enforceIssuance(),
                "local-mode（单机免登）即使开关误开也不强制");
        assertFalse(new ConversationIssuanceService(false, true).enforceIssuance());
    }
}
