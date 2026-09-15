// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 标签：同名不同型复用不改型。 */
public interface Tags {
    TagInfo getOrCreate(long projectId, String name, String type);
    void tagFile(long projectId, long fileId, long tagId);
    java.util.List<TagInfo> tagsOf(long projectId, long fileId);
}
