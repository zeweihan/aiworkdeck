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

    /**
     * 可写的 skill 目录（相对服务端工作目录，做法同 ai.plugins.dir）。
     * 广场安装/卸载只动这里；打包态它落在用户数据目录下。
     */
    private String dir = "skills";

    /**
     * 随发行版一同分发的**只读**内置 skill 目录（绝对路径，桌面端用
     * AI_SKILLS_BUILTIN_DIR 注入 Resources/skills）。
     *
     * <p>为什么要单独一个目录，而不是首启把内置 skill 拷进可写目录：
     * 一是拷过去之后广场的重装/卸载能覆盖甚至删掉内置 skill（卸载守卫只认
     * "来自插件"，认不出"来自发行版"）；二是升级后用户数据目录里会留着上个版本的
     * 陈旧副本，与新版本的内置 skill 静默打架。只读目录两个问题都不存在。
     *
     * <p>留空 = 没有内置目录（dev 态就是这样，内置 skill 在相对目录 skills/ 里）。
     */
    private String builtinDir = "";

    /**
     * 基础工具集：Skill 命中后本轮 LLM 可见工具 = allowed_tools ∪ base-tools。
     * 保证被裁剪的回合仍具备最基本的读取/记忆能力。
     */
    private List<String> baseTools = new ArrayList<>();

    /** 启停名单内存缓存 TTL（毫秒），做法同 ai.plugins.disabled-cache-ttl-ms */
    private long disabledCacheTtlMs = 5000;

    /** 在线 Skill 广场注册表地址（官网公共注册表，见 SkillMarketService） */
    private String registryUrl = "https://www.aiworkdeck.com/api/registry/skills";

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public String getBuiltinDir() { return builtinDir; }
    public void setBuiltinDir(String builtinDir) { this.builtinDir = builtinDir; }
    public List<String> getBaseTools() { return baseTools; }
    public void setBaseTools(List<String> baseTools) { this.baseTools = baseTools; }
    public long getDisabledCacheTtlMs() { return disabledCacheTtlMs; }
    public void setDisabledCacheTtlMs(long disabledCacheTtlMs) { this.disabledCacheTtlMs = disabledCacheTtlMs; }
    public String getRegistryUrl() { return registryUrl; }
    public void setRegistryUrl(String registryUrl) { this.registryUrl = registryUrl; }
}
