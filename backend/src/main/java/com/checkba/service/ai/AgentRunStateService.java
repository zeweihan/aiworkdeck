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
        /**
         * 模型主动反问（{@code <question>} 标签），等用户回答。
         *
         * 刻意不复用 AWAITING_APPROVAL：会话列表要把「待回答」与「待审批」显示成两种文案
         * （前者是模型缺信息不敢猜，后者是有草案等你点头），跨进程重启后也要能把
         * 「AI 在等你」与「AI 答完了」区分开。SSE 上的 status 字面量是 awaiting_input。
         *
         * 停机语义与 AWAITING_APPROVAL 完全一致：答案是**下一轮普通用户消息**，
         * 不是阻塞式挂起（工具分发跑在流式回调线程上，撞 600s callTimeout 与 180s
         * 无活动看门狗，且律师会关掉 app 明天再来）。
         */
        AWAITING_INPUT,
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

    /**
     * 每会话运行状态。<b>无界增长</b>：每个在本进程内跑过至少一轮的 conversationId 永久占一条，
     * 从不摘除（审计条目：AgentRunStateService.states map grows unboundedly for the life of
     * the process）。云端长命进程服务全租户会话，条目只涨不消。用 {@link #purgeStaleStates()}
     * 做惰性过期：7 天没有新状态更新的记录直接摘掉——超过这个窗口继续占着内存没有任何用户可见
     * 价值，{@link #get} 对"内存里没有记录"本来就当"无任务"正常渲染（历史会话的既有语义）。
     */
    private final Map<String, RunState> states = new ConcurrentHashMap<>();

    /** {@link #purgeStaleStates()} 的过期窗口，见 {@link #states} 字段注释。 */
    private static final long STALE_STATE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** 时间源，测试可注入固定值以避免真实等待 7 天。 */
    private java.util.function.LongSupplier clockMillis = System::currentTimeMillis;

    void setClockMillis(java.util.function.LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    private final AgentRunRecordRepository recordRepository;
    private final com.checkba.service.telemetry.TelemetryTurnTracker turnTracker;

    public AgentRunStateService(AgentRunRecordRepository recordRepository,
                                com.checkba.service.telemetry.TelemetryTurnTracker turnTracker) {
        this.recordRepository = recordRepository;
        this.turnTracker = turnTracker;
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
        states.put(conversationId, new RunState(status, clockMillis.getAsLong()));
        // 埋点：终态在此单点合成 ai.turn（新增终止分支只要走 mark 就自动覆盖）；
        // restore() 刻意不打点——启动回收是既有状态回放，进程内也没有未闭合轮次
        turnTracker.onStatus(conversationId, status.name());
        persist(conversationId, status, projectId, userId);
    }

    /**
     * 清理超过 {@link #STALE_STATE_MILLIS} 未再更新的会话状态（见 {@link #states} 字段注释）。
     * 与 {@code TodoListService.purgeStaleLists} / {@code SkillRouter.purgeStaleActivations}
     * 同款节奏：每日一次 + 错峰的初始延迟。只清内存 map，不碰 DB——持久化记录的清理策略是
     * 另一个决策，不在本次改动范围内。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 24L * 60 * 60 * 1000,
            initialDelay = 20L * 60 * 1000)
    public void purgeStaleStates() {
        try {
            long cutoff = clockMillis.getAsLong() - STALE_STATE_MILLIS;
            int before = states.size();
            states.entrySet().removeIf(e -> e.getValue().updatedAt() < cutoff);
            int removed = before - states.size();
            if (removed > 0) {
                log.info("清理冷 Agent 运行状态记录 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("Failed to purge stale agent run states", e);
        }
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
        states.put(conversationId, new RunState(status, clockMillis.getAsLong()));
    }

    /** null = 本进程内从未跑过（历史会话），前端按「无任务」处理。 */
    public RunState get(String conversationId) {
        return conversationId == null ? null : states.get(conversationId);
    }

    public String statusName(String conversationId) {
        RunState s = get(conversationId);
        return s == null ? null : s.status().name();
    }

    /** 供测试断言登记簿大小（不下沉成生产代码路径）。 */
    int statesSize() {
        return states.size();
    }
}
