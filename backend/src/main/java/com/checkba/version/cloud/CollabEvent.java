// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 案件库侧的协作事件（spec 2026-09-14 §2.3）——「谁在什么时候动了这份案卷」。
 *
 * <p>相当于程序员那边的远端 reflog：git 历史只记得「产出了哪一版」，记不住
 * 「谁交了稿」「谁签出了一份」「谁被加进来了」。这些事只有案件库这一侧知道，
 * 所以表落在服务端，桌面端经 {@code /api/cloud/projects/{id}/events} 代理读。
 *
 * <p>**只增不改**：没有任何更新路径，事件写进来就是历史。写失败一律吞掉
 * （见 {@link CollabEventService#record}）——记事件是旁白，不能把 push 或加人弄失败。
 */
@Entity
@Table(name = "collab_event",
        indexes = @Index(name = "idx_collab_event_project", columnList = "project_id, created_at"))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CollabEvent {

    /** 事件类型。库里存 {@link Enum#name()}，不存序号——加新类型不会错位既有行。 */
    public enum Kind {
        /** 案卷第一次被放进案件库（桌面端 shareToCloud 建项目那一刻）。 */
        SHARED,
        /** 某台设备第一次从这个仓库取内容（按 (projectId, tokenId) 首次）。 */
        CHECKOUT,
        /** 主线被推进（receive-pack 成功）。 */
        PUSH,
        /** 有人把最新稿取回了自己机器——唯一由客户端上报的类型。 */
        PULLED,
        MEMBER_ADDED,
        MEMBER_REMOVED,
        MEMBER_ROLE_CHANGED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 32)
    private String kind;

    /** 干这件事的人（案件库侧的 userId）。 */
    @Column(nullable = false)
    private Long actorUserId;

    /** 他用的那台设备（device_token 行 id）。成员变更一类没有设备，为 null。 */
    private Long tokenId;

    private String fromSha;

    private String toSha;

    /** 这次 push 推进了几版。非 PUSH 事件为 null。 */
    private Integer commitCount;

    /** 被操作的那个人（成员三事件）。 */
    private Long targetUserId;

    /** 类型相关的附加信息，JSON 对象文本。解析不出就当没有，绝不让一行坏数据挡住整张列表。 */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
