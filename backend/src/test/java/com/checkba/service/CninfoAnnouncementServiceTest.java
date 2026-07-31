package com.checkba.service;

import com.checkba.service.CninfoAnnouncementService.Announcement;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 巨潮公告挑选启发式（移植自 Python 版）的行为锁定：
 * 主通知优先、延期/更正/会议资料排除、董事会决议会前披露优先。
 */
class CninfoAnnouncementServiceTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private static long ms(String date) {
        return LocalDate.parse(date).atStartOfDay(CST).toInstant().toEpochMilli();
    }

    private static Announcement ann(String title, String date) {
        return new Announcement(title, ms(date), "id-" + title.hashCode(), "org1",
                "finalpage/" + date + "/x.PDF", "301128");
    }

    @Test
    void picksMainNoticeAndExcludesDeferredAndMaterials() {
        LocalDate meeting = LocalDate.parse("2026-01-15");
        List<Announcement> anns = List.of(
                ann("关于召开2026年第一次临时股东会的通知（延期后）", "2025-12-30"),
                ann("2026年第一次临时股东会会议资料", "2026-01-05"),
                ann("关于召开2026年第一次临时股东会的通知", "2025-12-25"),
                ann("2025年第三次临时股东会决议公告", "2025-12-01")
        );
        Announcement picked = CninfoAnnouncementService.pickShareholdersNotice(anns, meeting);
        assertNotNull(picked);
        assertEquals("关于召开2026年第一次临时股东会的通知", picked.title());
    }

    @Test
    void noticeReturnsNullWhenNoCandidate() {
        LocalDate meeting = LocalDate.parse("2026-01-15");
        List<Announcement> anns = List.of(
                ann("2026年第一次临时股东会决议公告", "2026-01-15")
        );
        assertNull(CninfoAnnouncementService.pickShareholdersNotice(anns, meeting));
    }

    @Test
    void noticePrefersClosestToMeetingDate() {
        LocalDate meeting = LocalDate.parse("2026-05-20");
        List<Announcement> anns = List.of(
                ann("关于召开2025年年度股东会的通知", "2026-04-01"),
                ann("关于召开2026年第二次临时股东会的通知", "2026-05-05")
        );
        Announcement picked = CninfoAnnouncementService.pickShareholdersNotice(anns, meeting);
        assertEquals("关于召开2026年第二次临时股东会的通知", picked.title());
    }

    @Test
    void picksBoardResolutionBeforeMeetingAndExcludesCommittees() {
        LocalDate meeting = LocalDate.parse("2026-01-15");
        List<Announcement> anns = List.of(
                ann("第三届审计委员会第五次会议决议公告", "2025-12-28"),
                ann("第三届董事会第七次（临时）会议决议公告", "2025-12-25"),
                // 会后披露的另一次董事会：应劣后于会前那次
                ann("第三届董事会第八次会议决议公告", "2026-01-20")
        );
        Announcement picked = CninfoAnnouncementService.pickBoardResolution(anns, meeting);
        assertNotNull(picked);
        assertEquals("第三届董事会第七次（临时）会议决议公告", picked.title());
    }

    @Test
    void boardResolutionFallsBackToAfterMeetingWhenNoneBefore() {
        LocalDate meeting = LocalDate.parse("2026-01-15");
        List<Announcement> anns = List.of(
                ann("第三届董事会第八次会议决议公告", "2026-01-20")
        );
        Announcement picked = CninfoAnnouncementService.pickBoardResolution(anns, meeting);
        assertNotNull(picked);
        assertEquals("第三届董事会第八次会议决议公告", picked.title());
    }

    @Test
    void pdfUrlJoinsStaticBase() {
        Announcement a = ann("关于召开2026年第一次临时股东会的通知", "2025-12-25");
        assertEquals("http://static.cninfo.com.cn/finalpage/2025-12-25/x.PDF", a.pdfUrl());
        Announcement empty = new Announcement("t", 0L, null, null, null, null);
        assertNull(empty.pdfUrl());
    }
}
