// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目文件批量操作请求
 *
 * 说明：
 * - move/copy：需要 targetParentId
 * - delete：仅使用 fileIds
 */
public class ProjectFileBatchRequest {
    /**
     * 需要操作的文件/文件夹 ID 列表
     */
    private List<Long> fileIds;

    /**
     * 目标父文件夹 ID（move/copy 使用；null 表示根目录）
     */
    private Long targetParentId;

    public List<Long> getFileIds() { return fileIds; }
    public void setFileIds(List<Long> fileIds) { this.fileIds = fileIds; }
    public Long getTargetParentId() { return targetParentId; }
    public void setTargetParentId(Long targetParentId) { this.targetParentId = targetParentId; }
}


