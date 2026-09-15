// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 证据链接的一个底稿目标。 */
public record TargetView(long id, long fileId, String fileName, String locatorJson, String relation, String method) {}
