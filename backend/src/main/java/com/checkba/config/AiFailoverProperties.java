package com.checkba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 故障转移链配置（对标 hermes-agent runtime_provider 的备选通道）。
 *
 * 配置前缀：ai.failover
 *
 * <p>只换模型、不换计费通道：备选模型仍由 ChatModelFactory 按当前生效的 provider 解析，
 * 平台通道（AWD_CLOUD）永远拿平台密钥，绝不会因为切模型而落到用户自己的 BYOK key。
 */
@Component
@ConfigurationProperties(prefix = "ai.failover")
public class AiFailoverProperties {

    /** 是否启用故障转移；关掉后重试预算耗尽即终局报错（与加固前行为一致）。 */
    private boolean enabled = true;

    /**
     * 备选模型链，按顺序尝试。只有 AllowedModels 白名单内的模型才有意义：
     * 非白名单模型会被工厂静默回落到默认模型，等于没切。
     */
    private List<String> models = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }
}
