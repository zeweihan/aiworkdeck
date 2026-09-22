// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectAiMessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectAiMessageAttachmentRepository
        extends JpaRepository<ProjectAiMessageAttachment, Long> {

    /** 历史回灌：一次把整条会话的附件取回来，绝不按消息逐条查（N+1）。 */
    List<ProjectAiMessageAttachment> findByMessageIdInOrderByIdAsc(List<Long> messageIds);

    /** 会话被删除时一并清掉。 */
    void deleteByMessageIdIn(List<Long> messageIds);
}
