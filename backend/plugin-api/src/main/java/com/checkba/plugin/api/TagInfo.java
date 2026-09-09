// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 标签；type 为 NORMAL / PARTY / ISSUE（null 视同 NORMAL）。 */
public record TagInfo(long id, String name, String type, String color) {}
