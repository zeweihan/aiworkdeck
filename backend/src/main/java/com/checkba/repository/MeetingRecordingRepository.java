// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.MeetingRecording;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingRecordingRepository extends JpaRepository<MeetingRecording, Long> {

    List<MeetingRecording> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<MeetingRecording> findByProjectIdAndStatusOrderByCreatedAtDesc(Long projectId, String status);

    /**
     * 「这个音频文件有没有转写稿」（dev-board#814）：audioFileId 就是音频与转写稿之间既有的
     * 那条关联，不需要再在 project_file 上加列。带 projectId 是防越界——调用方拿到的 fileId
     * 来自附件请求体/工具参数，与 ProjectFile 的归属必须对得上。
     *
     * <p>返回列表而不是单条：这一列没有唯一约束，存量库里同一个文件被注册过两次（#227 的
     * 幂等是在服务层做的、早期版本没有）就会有两行，取最新的那行。
     */
    List<MeetingRecording> findByProjectIdAndAudioFileIdOrderByCreatedAtDesc(Long projectId, Long audioFileId);
}
