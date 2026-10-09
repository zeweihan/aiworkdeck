// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.trial;

import java.time.Duration;

/** 试用计量 v0.1.1 冻结口径（规格 §1）。 */
public final class TrialPolicy {

    private TrialPolicy() {}

    public static final String POLICY_MIN_OF_DAYS_OR_CALLS = "min_of_days_or_calls";
    public static final int TRIAL_DAYS = 14;
    public static final int TRIAL_CALLS = 80;
    public static final Duration TRIAL_DURATION = Duration.ofDays(TRIAL_DAYS);

    public static final String STATUS_NONE = "none";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXHAUSTED_CALLS = "exhausted_calls";
    public static final String STATUS_EXPIRED_DAYS = "expired_days";
    public static final String STATUS_CONVERTED = "converted";

    /** 对外条款文案在 counsel 确认前只允许这一句（总经理 2026-10-09）。 */
    public static final String TERMS_NOTICE = "以协议为准";
}
