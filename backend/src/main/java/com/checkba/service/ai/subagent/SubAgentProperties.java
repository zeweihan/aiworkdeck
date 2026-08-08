package com.checkba.service.ai.subagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 子 Agent（Phase 3C 多智能体第一阶段）配置。
 *
 * 配置前缀：ai.subagent
 *
 * 遵循不变式 5（配置外置）：轮数上限、并行度、token 预算、超时等
 * 限值一律来自本配置，不写死在代码里。
 */
@Component
@ConfigurationProperties(prefix = "ai.subagent")
public class SubAgentProperties {

    /** 单个子任务的 Thought→Action→Observation 轮数上限 */
    private int maxRounds = 6;

    /** 子任务专用线程池大小 = 同时运行的子任务数上限 */
    private int maxParallel = 3;

    /**
     * 单个子任务的总超时（秒），超时返回明确错误结果、不挂死主循环。
     *
     * <p>取值必须 <b>大于单次 LLM 读超时</b>（yml {@code ai.model.open-router.timeout} = 600s）
     * 再留一点余量，630 = 600 + 30。原值 180s 与它自相矛盾：一次合法的慢调用还没返回，
     * 等待方就先放弃了，而 {@code future.cancel(true)} 的中断打不断阻塞在 socket 读上的 HTTP 调用——
     * 子 Agent 会继续跑完那一轮、照样烧 token，结果却被丢弃。
     * （超时后子 Agent 仍会在下一轮开头的 isInterrupted 检查处停下，所以最多浪费一次在途调用。）
     *
     * <p>代价是最坏情况下主会话要等 630s 才拿到「子任务超时」。要缩短这个上限，
     * 正确的做法是调小 LLM 的读超时，而不是把这里改回比它还小的值。
     */
    private int timeoutSeconds = 630;

    /**
     * 子 Agent 消息栈的 token 预算（估算值，超出即中止并返回错误结果）。
     *
     * <p>换算：charBudget = tokenBudget × charsPerToken。单个文件进上下文的上限是 50000 字符
     * （yml {@code ai.context.files.max-chars-per-file}）≈ 25000 token，原值 30000 token
     * （= 60000 字符）意味着「给了读文件工具的子任务，读一个文件就吃掉 5/6 预算」，
     * 第二轮必然撞预算失败。60000 token（≈120000 字符）能装下系统提示 + 任务描述 +
     * 两个满额文件 + 若干轮工具输出，且仍远低于白名单里所有模型的上下文窗口。
     */
    private int tokenBudget = 60000;

    /** token 估算系数：每个 token 约折合多少字符（与 ai.context.chars-per-token 口径一致） */
    private double charsPerToken = 2.0;

    /**
     * 子 Agent 使用的模型 ID（yml 档）。
     *
     * <p>解析链：system_setting {@code ai.subagentModel} → 本项 → 辅助模型
     * （{@code ai.auxModel} → yml {@code ai.aux-model}），见
     * {@code com.checkba.service.ai.AuxModelResolver}。留空<b>不再</b>继承主会话所选模型——
     * 长程任务的子任务用主模型最烧钱，默认走便宜的辅助模型。
     */
    private String model = "";

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public int getMaxParallel() { return maxParallel; }
    public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(int tokenBudget) { this.tokenBudget = tokenBudget; }
    public double getCharsPerToken() { return charsPerToken; }
    public void setCharsPerToken(double charsPerToken) { this.charsPerToken = charsPerToken; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
