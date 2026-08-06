package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 每会话 Agent 运行状态的持久化记录。
 *
 * <p>内存登记簿（AgentRunStateService）只在 JVM 存活期内有效，进程被杀/崩溃后清零；
 * 这张表让「杀 app 时正在跑的会话」在重启后仍可被识别（启动回收把 RUNNING 置为
 * INTERRUPTED），从而给用户一个「继续」入口，而不是留下一条没有结论的半截回复。
 */
@Entity
@Table(name = "agent_run_record")
@Getter
@Setter
public class AgentRunRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（唯一）：一个会话只保留最新一次运行的状态。 */
    @Column(name = "conversation_id", nullable = false, unique = true, length = 64)
    private String conversationId;

    /** 与 AgentRunStateService.RunStatus 同名的字面量。 */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentRunRecord that = (AgentRunRecord) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
