package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优化者（Optimizer Agent）：把用户反馈推成产品迭代的那一环。
 *
 * <p>一轮 = 取一批 status=NEW 的反馈 → 逐条分诊 → 分流到两条出口：
 * <ul>
 *   <li><b>确认是 bug 且有把握</b> → {@link OptimizerCodeFixRunner} 去改代码、开 PR（永不合并）。</li>
 *   <li><b>优化建议 / 拿不准 / 把握不够 / 改动被拦</b> → {@link OptimizerMailer} 发邮件请人定夺。</li>
 * </ul>
 *
 * <p><b>不变式：</b>
 * <ol>
 *   <li>反馈行只改状态，永不删除；每条的分诊结论与去向都回写同一行。</li>
 *   <li>一条反馈最多重试 {@code maxAttempts} 轮，之后停在 FAILED 等人；不会无限烧 token。</li>
 *   <li>两条出口都走不通时记 FAILED 并写明原因，**绝不悄悄标成已处理**。</li>
 *   <li>同时只允许一轮在跑。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizerAgentService {

    private final OptimizerProperties props;
    private final OptimizerFeedbackSource source;
    private final FeedbackTriageService triageService;
    private final OptimizerCodeFixRunner codeFixRunner;
    private final OptimizerMailer mailer;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile RunReport lastReport;
    private volatile LocalDateTime lastRunAt;

    public record ItemResult(Long feedbackId, String verdict, double confidence, String outcome, String detail) {
    }

    public record RunReport(int picked, int prOpened, int emailed, int skipped, int failed,
                            List<ItemResult> items, String note) {
    }

    /** 定时入口。总开关关着时什么都不做（桌面版用户机器上的默认状态）。 */
    @Scheduled(cron = "${optimizer.cron:0 0 9 * * *}")
    public void scheduledRun() {
        if (!props.isEnabled()) return;
        log.info("[optimizer] 定时轮次开始");
        runOnce();
    }

    public boolean isRunning() {
        return running.get();
    }

    public RunReport lastReport() {
        return lastReport;
    }

    public LocalDateTime lastRunAt() {
        return lastRunAt;
    }

    /** 跑一轮。返回本轮报告；总开关关着或已有一轮在跑时返回空报告并说明原因。 */
    public RunReport runOnce() {
        if (!props.isEnabled()) {
            return new RunReport(0, 0, 0, 0, 0, List.of(), "optimizer.enabled=false，未启用");
        }
        if (!running.compareAndSet(false, true)) {
            return new RunReport(0, 0, 0, 0, 0, List.of(), "已有一轮在跑");
        }
        try {
            List<UserFeedback> batch;
            try {
                batch = source.pending(Math.max(1, props.getBatchSize()), props.getMaxAttempts());
            } catch (Exception e) {
                // 取件失败（云端不可达/token 不对）：整轮空转但要说清楚，别只在日志里
                log.warn("[optimizer] 取件失败：{}", e.toString());
                RunReport failed = new RunReport(0, 0, 0, 0, 0, List.of(), "取件失败：" + e.getMessage());
                lastReport = failed;
                lastRunAt = LocalDateTime.now();
                return failed;
            }
            List<ItemResult> items = new ArrayList<>();
            int prOpened = 0, emailed = 0, skipped = 0, failed = 0;
            for (UserFeedback fb : batch) {
                ItemResult r;
                try {
                    r = processOne(fb);
                } catch (Exception e) {
                    // 一条炸了不该带走整轮（远端回执失败最容易走到这）
                    log.warn("[optimizer] 反馈 #{} 处理中断：{}", fb.getId(), e.toString());
                    r = new ItemResult(fb.getId(), "", 0, UserFeedback.STATUS_FAILED, String.valueOf(e.getMessage()));
                }
                items.add(r);
                switch (r.outcome()) {
                    case UserFeedback.STATUS_PR_OPENED -> prOpened++;
                    case UserFeedback.STATUS_EMAILED -> emailed++;
                    case UserFeedback.STATUS_SKIPPED -> skipped++;
                    case UserFeedback.STATUS_FAILED -> failed++;
                    default -> {
                        // 演练模式：状态保持 NEW，不计入任何出口
                    }
                }
            }
            RunReport report = new RunReport(batch.size(), prOpened, emailed, skipped, failed, items,
                    props.isDryRun() ? "演练模式：只分诊，不开 PR、不发邮件、不改状态" : "");
            lastReport = report;
            lastRunAt = LocalDateTime.now();
            log.info("[optimizer] 本轮结束：取 {} 条，PR {}，邮件 {}，跳过 {}，失败 {}",
                    report.picked(), prOpened, emailed, skipped, failed);
            return report;
        } finally {
            running.set(false);
        }
    }

    private ItemResult processOne(UserFeedback fb) {
        List<FeedbackAttachment> attachments = source.attachmentsOf(fb);
        // 尝试次数在最前面加：分诊本身挂掉也要计数，否则一条永远分诊失败的反馈
        // 每轮都会被重新捞出来，永远到不了 maxAttempts（白烧 token）
        if (!props.isDryRun()) {
            fb.setAttempts(fb.getAttempts() + 1);
        }

        FeedbackTriageService.TriageResult triage;
        try {
            triage = triageService.triage(fb, attachments, props.getModel());
        } catch (Exception e) {
            return fail(fb, "分诊失败: " + e.getMessage(), null);
        }

        recordTriage(fb, triage);

        if (props.isDryRun()) {
            source.save(fb);
            return new ItemResult(fb.getId(), triage.verdict(), triage.confidence(), "DRY_RUN",
                    "演练模式，未执行任何出口");
        }

        if (FeedbackTriageService.VERDICT_NOISE.equals(triage.verdict())) {
            fb.setStatus(UserFeedback.STATUS_SKIPPED);
            fb.setHandledAt(LocalDateTime.now());
            source.save(fb);
            return new ItemResult(fb.getId(), triage.verdict(), triage.confidence(),
                    UserFeedback.STATUS_SKIPPED, triage.reason());
        }

        if (triage.isBug() && triage.confidence() >= props.getMinConfidence()) {
            OptimizerCodeFixRunner.FixOutcome outcome = codeFixRunner.run(fb, triage);
            switch (outcome.status()) {
                case PR_OPENED -> {
                    fb.setStatus(UserFeedback.STATUS_PR_OPENED);
                    fb.setPrUrl(outcome.prUrl());
                    fb.setLastError(null);
                    fb.setHandledAt(LocalDateTime.now());
                    source.save(fb);
                    return new ItemResult(fb.getId(), triage.verdict(), triage.confidence(),
                            UserFeedback.STATUS_PR_OPENED, outcome.prUrl());
                }
                case NO_CHANGES, BLOCKED -> {
                    // 判成 bug 但改不出来（或改到了受保护路径）：这恰恰是最该问人的情况
                    return emailOrFail(fb, triage, attachments, outcome.detail());
                }
                default -> {
                    return fail(fb, outcome.detail(), triage);
                }
            }
        }

        return emailOrFail(fb, triage, attachments, lowConfidenceNote(triage));
    }

    private String lowConfidenceNote(FeedbackTriageService.TriageResult triage) {
        if (triage.isBug()) {
            return "判成了缺陷但置信度 " + String.format("%.2f", triage.confidence())
                    + " 低于开 PR 的门槛 " + String.format("%.2f", props.getMinConfidence()) + "，改成问你。";
        }
        return "";
    }

    private ItemResult emailOrFail(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                                   List<FeedbackAttachment> attachments, String note) {
        if (!mailer.isAvailable()) {
            return fail(fb, "需要邮件出口但它不可用：" + mailer.unavailableReason(), triage);
        }
        try {
            mailer.send(fb, triage, attachments, source, note);
        } catch (Exception e) {
            return fail(fb, "发邮件失败: " + e.getMessage(), triage);
        }
        fb.setStatus(UserFeedback.STATUS_EMAILED);
        fb.setLastError(null);
        fb.setHandledAt(LocalDateTime.now());
        source.save(fb);
        return new ItemResult(fb.getId(), triage.verdict(), triage.confidence(),
                UserFeedback.STATUS_EMAILED, note);
    }

    /** 本轮没成：没到重试上限就退回 NEW 等下一轮，到了上限停在 FAILED 等人看。 */
    private ItemResult fail(UserFeedback fb, String reason, FeedbackTriageService.TriageResult triage) {
        fb.setStatus(fb.getAttempts() >= props.getMaxAttempts()
                ? UserFeedback.STATUS_FAILED : UserFeedback.STATUS_NEW);
        fb.setLastError(reason);
        source.save(fb);
        log.warn("[optimizer] 反馈 #{} 本轮未处理成功：{}", fb.getId(), reason);
        return new ItemResult(fb.getId(), triage == null ? "" : triage.verdict(),
                triage == null ? 0 : triage.confidence(), UserFeedback.STATUS_FAILED, reason);
    }

    private void recordTriage(UserFeedback fb, FeedbackTriageService.TriageResult triage) {
        fb.setTriageVerdict(triage.verdict());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verdict", triage.verdict());
        m.put("confidence", triage.confidence());
        m.put("title", triage.title());
        m.put("summary", triage.summary());
        m.put("severity", triage.severity());
        m.put("reason", triage.reason());
        m.put("at", LocalDateTime.now().toString());
        try {
            fb.setTriageJson(mapper.writeValueAsString(m));
        } catch (Exception e) {
            fb.setTriageJson("{}");
        }
    }
}
