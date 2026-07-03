package com.checkba.service.ai.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 体系配置（Phase 3B，规范见 docs/SKILL_SPEC.md）。
 *
 * 配置前缀：ai.skills
 */
@Component
@ConfigurationProperties(prefix = "ai.skills")
public class SkillProperties {

    /** skill 扫描目录（相对服务端工作目录，做法同 ai.plugins.dir） */
    private String dir = "skills";

    /**
     * 基础工具集：Skill 命中后本轮 LLM 可见工具 = allowed_tools ∪ base-tools。
     * 保证被裁剪的回合仍具备最基本的读取/记忆能力。
     */
    private List<String> baseTools = new ArrayList<>();

    /** 启停名单内存缓存 TTL（毫秒），做法同 ai.plugins.disabled-cache-ttl-ms */
    private long disabledCacheTtlMs = 5000;

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public List<String> getBaseTools() { return baseTools; }
    public void setBaseTools(List<String> baseTools) { this.baseTools = baseTools; }
    public long getDisabledCacheTtlMs() { return disabledCacheTtlMs; }
    public void setDisabledCacheTtlMs(long disabledCacheTtlMs) { this.disabledCacheTtlMs = disabledCacheTtlMs; }
}
