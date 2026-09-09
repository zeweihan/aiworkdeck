// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** status 取值：queued / running / done / failed / cancelled。 */
public record JobStatus(String jobId, String kind, String title, String status, long done, long total,
                        String message, String resultJson, String error) {}
