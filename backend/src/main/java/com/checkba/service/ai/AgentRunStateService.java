package com.checkba.service.ai;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.repository.AgentRunRecordRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每会话 Agent 运行状态登记簿。
 *
 * 背景：Agent 循环跑在 @Async 线程上，SSE 断开（用户切走会话）后循环继续执行，
 * 但此前没有任何地方记录「哪个会话在跑 / 在等审批 / 已跑完」——会话列表因此
 * 无法显示后台任务状态，前端切回会话也无从判断要不要重连续流。
 *
 * 内存 map 是快路径；同时写透到 agent_run_record 表（写失败只记日志，不阻断循环）。
 * 持久化的唯一用途是「进程被杀后还能认出当时在跑的会话」：启动时
 * {@link AgentRunRecoveryService} 把残留的 RUNNING 回收成 INTERRUPTED 并塞回内存 map，
 * 前端据此渲染「继续」入口。除此之外，进程内的状态判断一律只看内存
 * （FINISHED/ERROR/CANCELLED 不跨重启复活，避免出现「显示运行中但循环已死」的僵尸状态）。
 */
@Service
public class AgentRunStateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRunStateService.class);

    /** 会话运行状态。前端状态点依赖字面量，改名要同步 useAgentStream/历史列表。 */
    public enum RunStatus {
        /** 循环执行中 */
        RUNNING,
        /** 步数超限暂停，等用户点「继续」 */
        PAUSED,
        /** Plan 模式等用户审批 */
        AWAITING_APPROVAL,
        /** 正常跑完 */
        FINISHED,
        /** 异常终止 */
        ERROR,
        /** 用户主动取消 */
        CANCELLED,
        /** 上次进程执行中被杀（崩溃/关 app/断电），重启后回收出来，等用户点「继续」 */
        INTERRUPTED,
    }

    public record RunState(RunStatus status, long updatedAt) {}

    private final Map<String, RunState> states = new ConcurrentHashMap<>();

    private final AgentRunRecordRepository recordRepository;

    public AgentRunStateService(AgentRunRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    public void mark(String conversationId, RunStatus status) {
        mark(conversationId, status, null, null);
    }

    /**
     * 登记状态并写透到 DB。projectId/userId 为 null 时保留记录里已有的值
     * （只有循环起跑那一次拿得到它们，后续终态打点不该把它们抹掉）。
     */
    public void mark(String conversationId, RunStatus status, Long projectId, Long userId) {
        if (conversationId == null || status == null) return;
        states.put(conversationId, new RunState(status, System.currentTimeMillis()));
        persist(conversationId, status, projectId, userId);
    }

    private void persist(String conversationId, RunStatus status, Long projectId, Long userId) {
        try {
            AgentRunRecord record = recordRepository.findByConversationId(conversationId)
                    .orElseGet(AgentRunRecord::new);
            record.setConversationId(conversationId);
            record.setStatus(status.name());
            if (projectId != null) record.setProjectId(projectId);
            if (userId != null) record.setUserId(userId);
            record.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(record);
        } catch (Exception e) {
            // 持久化只服务于「重启后可见」，失败不能影响正在跑的循环
            log.warn("Failed to persist agent run state: conv={}, status={}", conversationId, status, e);
        }
    }

    /**
     * 只写内存、不回写 DB：启动回收把持久化记录塞回快路径时用。
     * （DB 里已经是这个状态了，再 save 一遍纯属浪费。）
     */
    void restore(String conversationId, RunStatus status) {
        if (conversationId == null || status == null) return;
        states.put(conversationId, new RunState(status, System.currentTimeMillis()));
    }

    /** null = 本进程内从未跑过（历史会话），前端按「无任务」处理。 */
    public RunState get(String conversationId) {
        return conversationId == null ? null : states.get(conversationId);
    }

    public String statusName(String conversationId) {
        RunState s = get(conversationId);
        return s == null ? null : s.status().name();
    }
}
