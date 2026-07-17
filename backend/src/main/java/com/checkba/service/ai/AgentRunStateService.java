package com.checkba.service.ai;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每会话 Agent 运行状态登记簿（内存态）。
 *
 * 背景：Agent 循环跑在 @Async 线程上，SSE 断开（用户切走会话）后循环继续执行，
 * 但此前没有任何地方记录「哪个会话在跑 / 在等审批 / 已跑完」——会话列表因此
 * 无法显示后台任务状态，前端切回会话也无从判断要不要重连续流。
 *
 * 状态仅在 JVM 存活期内有效：进程重启后循环本身已不存在，登记簿清零与事实一致
 * （不会出现「显示运行中但循环已死」的僵尸状态）。
 */
@Service
public class AgentRunStateService {

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
    }

    public record RunState(RunStatus status, long updatedAt) {}

    private final Map<String, RunState> states = new ConcurrentHashMap<>();

    public void mark(String conversationId, RunStatus status) {
        if (conversationId == null) return;
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
