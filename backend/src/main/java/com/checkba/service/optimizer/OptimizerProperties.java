package com.checkba.service.optimizer;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优化者（Optimizer Agent）配置，前缀 {@code optimizer}。
 *
 * <p><b>默认整体关闭</b>：这是维护者侧的能力（要有仓库工作副本、gh 登录、编码 Agent CLI
 * 和一个收件箱），装在用户机器上的桌面版永远不该自己跑起来去开 PR。
 * 打开它的是维护者自己的后端实例或团队服务器：{@code optimizer.enabled=true}。
 */
@Component
@ConfigurationProperties(prefix = "optimizer")
@Getter
@Setter
public class OptimizerProperties {

    /** 总开关。关着时定时任务与手动触发都直接拒绝。 */
    private boolean enabled = false;

    /** 调度表达式（Spring cron，6 段）。默认每天 09:00 跑一轮。 */
    private String cron = "0 0 9 * * *";

    /** 每轮最多处理多少条反馈——一轮开十几个 PR 没人看得完。 */
    private int batchSize = 5;

    /** 单条反馈最多重试几轮，超过就停在 FAILED 等人看。 */
    private int maxAttempts = 3;

    /** 演练模式：照常分诊并写库，但不推分支、不开 PR、不发邮件。 */
    private boolean dryRun = false;

    /** 分诊用模型；留空走 ChatModelFactory 的默认模型。 */
    private String model = "";

    /** 判成 bug 且置信度不低于此值才会去改代码，否则降级走邮件问人。 */
    private double minConfidence = 0.7;

    /**
     * 反馈来源：local（默认，读本进程的库）| remote（读云端收件箱）。
     * 生产形态是 remote——收件箱在云上收各安装的反馈，优化者跑在维护者自己的机器上。
     */
    private String source = "local";

    private final Remote remote = new Remote();
    private final Repo repo = new Repo();
    private final Agent agent = new Agent();
    private final Mail mail = new Mail();
    private final Notify notify = new Notify();

    @Getter
    @Setter
    public static class Notify {
        /**
         * 通知出口：auto（默认，有邮件用邮件、没有就开 Issue）| mail | issue | both。
         * 默认自己降级，是为了让「反馈没人管」不取决于有没有腾出时间去配 SMTP 授权码。
         */
        private String channel = "auto";
        /** 开 Issue 时打的标签；仓库里没建过的标签会被自动去掉重试。 */
        private List<String> issueLabels = List.of("user-feedback");
    }

    @Getter
    @Setter
    public static class Remote {
        /** 云端收件箱地址，如 https://addin.aiworkdeck.com */
        private String baseUrl = "";
        /** 与收件箱 feedback.optimizer-token 相同的共享密钥 */
        private String token = "";
        /**
         * 通知正文里「在浏览器里看这条反馈」的地址模板，{id} 会被替换成反馈 id。
         * 留空用默认的云端反馈控制台（baseUrl + /feedback-console/?fb={id}）；
         * 官网 admin 并入反馈看板后可指到那里，如
         * https://www.aiworkdeck.com/zh/admin?tab=feedback&fb={id}
         */
        private String consoleUrl = "";
    }

    @Getter
    @Setter
    public static class Repo {
        /** 仓库工作副本的绝对路径（维护者机器上的一份 clone）。 */
        private String path = "";
        /** 基线分支：PR 的目标分支，也是建工作分支的起点。 */
        private String baseBranch = "master";
        private String remote = "origin";
        private String branchPrefix = "optimizer/feedback-";
    }

    @Getter
    @Setter
    public static class Agent {
        /**
         * 编码 Agent 命令行。占位符 {@code {promptFile}} 会被换成本轮任务书的绝对路径，
         * {@code {prompt}} 会被换成任务书正文。
         * 默认用 Claude Code 无头模式——维护者本来就用它写这个仓库。
         */
        private List<String> command = List.of(
                "claude", "-p", "{prompt}",
                "--permission-mode", "acceptEdits");
        /** 单条任务的墙钟上限。 */
        private int timeoutSeconds = 1800;
    }

    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = true;
        /** 收件人（维护者），逗号分隔。留空 = 邮件出口不可用，会退化成只写库并记 FAILED。 */
        private String to = "";
        private String subjectPrefix = "[AI WorkDeck 优化者]";
    }
}
