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

    /** 单个子任务的总超时（秒），超时返回明确错误结果、不挂死主循环 */
    private int timeoutSeconds = 180;

    /** 子 Agent 消息栈的 token 预算（估算值，超出即中止并返回错误结果） */
    private int tokenBudget = 30000;

    /** token 估算系数：每个 token 约折合多少字符（与 ai.context.chars-per-token 口径一致） */
    private double charsPerToken = 2.0;

    /** 子 Agent 使用的模型 ID；为空则继承主会话所选模型 */
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
