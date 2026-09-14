// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollabEventRepository extends JpaRepository<CollabEvent, Long> {

    /**
     * 倒序一页。排序键是 (createdAt desc, id desc)：同一秒内落的几条（一次 push 带
     * 成员变更这种）光按时间排是不稳定的，翻页会漏行或重行。
     */
    List<CollabEvent> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);

    /** 游标翻页：id 比 before 小的下一页（id 单调递增，与上面的次序键方向一致）。 */
    List<CollabEvent> findByProjectIdAndIdLessThanOrderByCreatedAtDescIdDesc(
            Long projectId, Long before, Pageable pageable);

    /** CHECKOUT 去重：这台设备是不是第一次来取这个仓库。 */
    boolean existsByProjectIdAndKindAndTokenId(Long projectId, String kind, Long tokenId);
}
