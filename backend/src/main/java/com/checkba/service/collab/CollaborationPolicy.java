// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 可整体替换的资格判定层（spec 2026-09-10 §2 裁决 2）。
 *
 * <p>当前形态是"至少同一律所；没有律所的，同一团队"。将来律师之间只按项目连接时，
 * 把 {@code collab.eligibility.policy} 切到 {@code open} 即可——**上下游一行不动**。
 * 所以任何新的资格条件都要落在这一层的实现里，不许散到 ProjectMemberService 或控制器上。
 */
public interface CollaborationPolicy {
    Verdict check(OrgMembership requester, OrgMembership candidate);
}
