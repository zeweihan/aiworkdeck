// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 全文搜索结果响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    private int totalMatches;
    private int totalFiles;
    private List<FileSearchResult> results;

    /**
     * 单个文件的搜索结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileSearchResult {
        private Long fileId;
        private String wpsFileId;  // WPS 文件 ID，用于打开编辑器
        private String fileName;
        private String filePath;
        private String fileType;
        private int matchCount;
        private List<MatchInfo> matches;
        private List<com.checkba.model.entity.Tag> tags;
    }

    /**
     * 单个匹配项信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchInfo {
        private int lineNumber;
        private String content;       // 匹配行上下文内容
        private int startIndex;       // 匹配开始位置
        private int endIndex;         // 匹配结束位置
    }
}
