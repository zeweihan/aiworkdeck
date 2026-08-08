package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 每会话 Agent 任务清单的持久化记录（对应 TodoListService 的内存快路径）。
 *
 * <p>为什么单独一张表，而不是往 {@link AgentRunRecord} 上加一列：
 * ① 写频率与生命周期完全不同——run 状态每次状态迁移都写（起跑/暂停/终态），
 * 清单只在模型调 todo_write 时写；挂在同一行上会让每次状态打点都带着整个 JSON 一起重写，
 * 且两条写路径（mark 与 update）各自 findByConversationId→save，后写的会把先写的字段读到的旧值盖回去。
 * ② 清理口径不同：run 记录要留着给启动回收认「继续」入口，清单是纯恢复用的大字段，可以更早清掉。
 * ③ 语义上 run 记录是状态机快照，清单是任务内容，混在一行里以后加字段只会越来越糊。
 *
 * <p>存整表 JSON 而不是每项一行：todo_write 本来就是「整表覆写、无部分更新」的语义
 * （见 TodoListService 类注释），拆成明细行只会引入顺序列与删旧插新的事务，换不来任何查询能力。
 */
@Entity
@Table(name = "agent_todo_list")
@Getter
@Setter
public class AgentTodoList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID（唯一）：一个会话只保留最新一次覆写的清单。 */
    @Column(name = "conversation_id", nullable = false, unique = true, length = 64)
    private String conversationId;

    /** 归一化之后的清单 JSON 数组，元素形如 {"content":..,"activeForm":..,"status":..}。 */
    @Column(name = "todos_json", columnDefinition = "TEXT")
    private String todosJson;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentTodoList that = (AgentTodoList) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
