// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmeetMeetingItem {
    private String meetingId;
    private String meetingCode;
    private String subject;
    private String meetingType;
    private String subMeetingId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    @Builder.Default
    private List<TmeetRecordFile> records = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TmeetRecordFile {
        private String recordFileId;
        private String subject;
        private String duration;
        private String type; // "文字转写" / "云录制"
        private String permissionStatus; // "can_view"
        private String url;
        private LocalDateTime mediaStartTime;
    }
}
