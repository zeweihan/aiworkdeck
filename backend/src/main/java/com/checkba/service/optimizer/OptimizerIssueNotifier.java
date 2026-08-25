package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「建议 / 拿不准」开成一条 GitHub Issue。
 *
 * <p>零新增凭据：优化者本来就要有能推分支、开 PR 的 `gh` 登录，开 Issue 用的是同一把。
 * 相比邮件还多两个好处——有编号可追、能回头搜；反馈的去向地址（Issue URL）会随回执写回
 * 反馈记录，后台看板上直接点得开。
 *
 * <p>与开 PR 那条出口共用同一个仓库工作副本，但**只调 `gh issue create`**，不碰工作区、
 * 不建 worktree、不产生任何提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizerIssueNotifier implements OptimizerNotifier {

    private final OptimizerProperties props;
    private final ProcessRunner processRunner;

    @Override
    public String name() {
        return "GitHub Issue";
    }

    @Override
    public boolean isAvailable() {
        String repo = props.getRepo().getPath();
        return repo != null && !repo.isBlank() && new File(repo, ".git").exists();
    }

    @Override
    public String unavailableReason() {
        String repo = props.getRepo().getPath();
        if (repo == null || repo.isBlank()) return "未配置 optimizer.repo.path（开 Issue 也要在仓库目录里跑 gh）";
        if (!new File(repo, ".git").exists()) return "optimizer.repo.path 不是 git 仓库: " + repo;
        return "";
    }

    @Override
    public String notify(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                         List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        if (!isAvailable()) throw new IllegalStateException(unavailableReason());
        Path bodyFile = null;
        try {
            bodyFile = Files.createTempFile("awd-optimizer-issue-", ".md");
            Files.writeString(bodyFile, body(fb, triage, attachments, source, extraNote), StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(
                    "gh", "issue", "create",
                    "--title", title(fb, triage),
                    "--body-file", bodyFile.toString()));
            for (String label : props.getNotify().getIssueLabels()) {
                if (label != null && !label.isBlank()) {
                    cmd.add("--label");
                    cmd.add(label.trim());
                }
            }
            ProcessRunner.Result r = processRunner.run(cmd, new File(props.getRepo().getPath()), 120);
            if (!r.ok()) {
                // 标签不存在是 gh 最常见的失败（仓库里没建过这个 label），去掉标签重试一次，
                // 不能因为一个标签让整条出口不可用
                if (r.tail(500).contains("label")) {
                    log.warn("[optimizer] 开 Issue 带标签失败，去掉标签重试：{}", r.tail(200));
                    r = processRunner.run(cmd.subList(0, 7), new File(props.getRepo().getPath()), 120);
                }
                if (!r.ok()) throw new IllegalStateException("开 Issue 失败: " + r.tail(500));
            }
            String url = firstIssueUrl(r.stdout());
            log.info("[optimizer] 反馈 #{} 已开成 Issue {}", fb.getId(), url);
            return url;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("开 Issue 失败: " + e.getMessage(), e);
        } finally {
            if (bodyFile != null) {
                try {
                    Files.deleteIfExists(bodyFile);
                } catch (Exception ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    static String firstIssueUrl(String stdout) {
        for (String line : (stdout == null ? "" : stdout).split("\\R")) {
            String s = line.trim();
            if (s.startsWith("https://") && s.contains("/issues/")) return s;
        }
        return "";
    }

    String title(UserFeedback fb, FeedbackTriageService.TriageResult triage) {
        String head = FeedbackTriageService.VERDICT_SUGGESTION.equals(triage.verdict()) ? "建议" : "待定夺";
        String t = (triage.title() == null || triage.title().isBlank())
                ? "用户反馈 #" + fb.getId() : triage.title().trim();
        return "[用户反馈] " + head + "：" + t;
    }

    String body(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("> 由**优化者**从一条用户反馈自动开出，等你定夺要不要做、做到什么程度。\n")
                .append("> 不做就直接关掉；这条反馈在后台的状态已经是「已通知」，不会再被重复处理。\n\n");

        sb.append("## 用户原话（反馈 #").append(fb.getId()).append("）\n\n");
        String text = blank(fb.getText()) ? "（没写文字）" : fb.getText().trim();
        for (String line : text.split("\\R")) sb.append("> ").append(line).append('\n');
        if (!blank(fb.getVoiceTranscript())) {
            sb.append("\n**语音转写**：").append(fb.getVoiceTranscript().trim()).append('\n');
        }

        sb.append("\n## 分诊结论\n\n")
                .append("| 项 | 值 |\n|---|---|\n")
                .append("| 判定 | ").append(triage.verdict()).append(" |\n")
                .append("| 置信度 | ").append(String.format("%.2f", triage.confidence())).append(" |\n")
                .append("| 严重度 | ").append(nz(triage.severity())).append(" |\n")
                .append("| 提交页面 | ").append(nz(fb.getPage())).append(" |\n")
                .append("| 版本 / 平台 | ").append(nz(fb.getAppVersion())).append(" / ")
                .append(nz(fb.getPlatform())).append(" |\n\n")
                .append(nz(triage.summary())).append("\n\n")
                .append("**依据**：").append(nz(triage.reason())).append('\n');

        if (!blank(extraNote)) {
            sb.append("\n## 为什么没直接开 PR\n\n").append(extraNote.trim()).append('\n');
        }

        if (attachments != null && !attachments.isEmpty()) {
            sb.append("\n## 附件\n\n");
            for (FeedbackAttachment a : attachments) {
                sb.append("- ").append(a.getType()).append(' ')
                        .append(source == null ? a.getStoredName() : source.attachmentRef(fb, a)).append('\n');
            }
            sb.append("\n附件裸地址在浏览器里会 403（要带凭据），命令行取用取件密钥——\n")
                    .append("`curl -H \"X-Optimizer-Token: <token>\" '<地址>' -o shot.png`\n");
            boolean audioNoTranscript = attachments.stream()
                    .anyMatch(a -> FeedbackAttachment.TYPE_AUDIO.equals(a.getType()))
                    && blank(fb.getVoiceTranscript());
            if (audioNoTranscript) {
                sb.append("\n> 这条带语音但没有转写，内容需要你亲自听一遍。\n");
            }
        }

        // 反馈控制台直达（有浏览器入口的来源才有）
        String console = source == null ? null : source.consoleRef(fb);
        if (console != null && !console.isBlank()) {
            sb.append("\n在浏览器里看这条反馈（登录 admin 后直接看截图、听语音）：").append(console).append('\n');
        }
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
