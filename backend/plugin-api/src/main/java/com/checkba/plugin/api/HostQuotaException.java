// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 每插件每分钟宿主调用次数超限。 */
public class HostQuotaException extends RuntimeException {
    public HostQuotaException(String m) { super(m); }
}
