package com.checkba.service.ai;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 进程重启后的运行状态回收。
 *
 * <p>杀 app / 后端崩溃 / 断电时，正在跑的 Agent 循环随进程消失，DB 里留下一条只有执行
 * 日志、没有结论的半截 ASSISTANT 消息，而内存登记簿清零后前端完全看不出发生过什么。
 * 这里在启动时把残留的 RUNNING 回收成 INTERRUPTED，并在那条半截回复末尾补一行中断说明，
 * 前端据此渲染「继续」按钮——点击后模型带着已持久化的执行日志接着干。
 *
 * <p>刻意不做 runLoop 中途快照重放：工具副作用（写文档、发请求）无法保证幂等，
 * 重放最后一步的代价远大于收益。恢复粒度就是「从已持久化的轮次级执行日志继续」，
 * 增量保存机制（每轮工具执行后 saveAssistantMessage）保证丢失窗口只有最后一个未完成的 LLM 轮。
 */
@Service
public class AgentRunRecoveryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRunRecoveryService.class);

    /** 追加到半截回复末尾的中断说明（中文标记）；口径对齐取消路径的 [已中断]。 */
    static final String INTERRUPT_MARKER_ZH = "[进程中断]";
    /** 英文标记。幂等检查必须两个标记都认（见 {@link #appendInterruptNotice}）。 */
    static final String INTERRUPT_MARKER_EN = "[Process interrupted]";

    static final String INTERRUPT_NOTICE_ZH =
            "\n\n> **" + INTERRUPT_MARKER_ZH + "** 任务执行中应用被关闭，点击下方「继续」按钮可接着执行。";
    static final String INTERRUPT_NOTICE_EN =
            "\n\n> **" + INTERRUPT_MARKER_EN + "** The app was closed while this task was running. Click the Continue button below to resume.";

    /** 按应用语言取本次要写入的中断说明。 */
    static String interruptNotice() {
        return com.checkba.service.LangText.of(INTERRUPT_NOTICE_ZH, INTERRUPT_NOTICE_EN);
    }

    private final AgentRunRecordRepository recordRepository;
    private final ProjectAiMessageRepository messageRepository;
    private final AgentRunStateService agentRunStateService;

    public AgentRunRecoveryService(AgentRunRecordRepository recordRepository,
                                   ProjectAiMessageRepository messageRepository,
                                   AgentRunStateService agentRunStateService) {
        this.recordRepository = recordRepository;
        this.messageRepository = messageRepository;
        this.agentRunStateService = agentRunStateService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            int recovered = recoverInterruptedRuns();
            if (recovered > 0) {
                log.info("Recovered {} interrupted agent run(s) from previous process", recovered);
            }
        } catch (Exception e) {
            // 回收失败只影响「中断可见」，绝不能挡住应用启动
            log.warn("Agent run recovery failed", e);
        }
    }

    /**
     * 把上次进程遗留的 RUNNING 全部回收为 INTERRUPTED，并给对应会话的最后一条
     * ASSISTANT 消息补中断说明；同时把已经是 INTERRUPTED 的记录塞回内存快路径
     * （用户一直没点「继续」又重启了几次时，「继续」入口不能凭空消失）。
     *
     * @return 本次新回收的会话数
     */
    public int recoverInterruptedRuns() {
        // 已回收但用户尚未续跑的会话：只恢复内存，不重复补标记
        for (AgentRunRecord record : recordRepository.findByStatus(
                AgentRunStateService.RunStatus.INTERRUPTED.name())) {
            agentRunStateService.restore(record.getConversationId(),
                    AgentRunStateService.RunStatus.INTERRUPTED);
        }

        List<AgentRunRecord> stale = recordRepository.findByStatus(AgentRunStateService.RunStatus.RUNNING.name());
        int count = 0;
        for (AgentRunRecord record : stale) {
            String conversationId = record.getConversationId();
            if (conversationId == null) continue;
            try {
                // mark 同时写内存与 DB：内存这一份是 /connect 推 run_state 的唯一数据源
                agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.INTERRUPTED);
                appendInterruptNotice(conversationId);
                count++;
            } catch (Exception e) {
                log.warn("Failed to recover interrupted run: conv={}", conversationId, e);
            }
        }
        return count;
    }

    /**
     * 给会话最后一条 ASSISTANT 消息补中断说明。
     * 崩在第一个 token 之前（没有任何 ASSISTANT 行）时什么都不做——状态标记已足够让
     * 前端显示「继续」，凭空插一条只有说明的回复反而更难看。
     */
    private void appendInterruptNotice(String conversationId) {
        List<ProjectAiMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        ProjectAiMessage last = null;
        for (ProjectAiMessage m : messages) {
            if ("ASSISTANT".equalsIgnoreCase(m.getRole())) {
                last = m;
            }
        }
        if (last == null) return;
        String content = last.getContent() == null ? "" : last.getContent();
        // 幂等：重复启动（或回收跑了两次）不该把说明叠加成一串。
        // 中英两个标记都要认——切换语言后重启（或升级后存量中文半截消息）不能被二次追加
        if (content.contains(INTERRUPT_MARKER_ZH) || content.contains(INTERRUPT_MARKER_EN)) return;
        last.setContent(content + interruptNotice());
        messageRepository.save(last);
    }
}
