// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRouter 供应商路由偏好（dev-board#1061）。配置前缀：ai.provider-routing
 *
 * <p>请求体带 {@code "provider":{"sort":...,"quantizations":[...],"allow_fallbacks":...}}。
 * <b>适用范围与 {@code ai.reasoning.models} 相同</b>（只对名单里的思考型模型注入），不对全部模型注入：
 * {@code quantizations} 是硬过滤，单供应商模型（qwen 只有 Alibaba、seed 只有 Seed）一旦上游改了量化标注
 * 就会整条 404；而 OpenRouter 文档写明手动路由偏好会影响提示缓存的供应商粘滞。这两件事对
 * 名单外的模型都是纯风险、没有收益。
 *
 * <p>类里的默认值是「关」：未绑定配置的实例（手工 new 的测试）行为与改动前一致，开关在 yml。
 */
@Component
@ConfigurationProperties(prefix = "ai.provider-routing")
public class AiProviderRoutingProperties {

    private boolean enabled = false;

    /** latency / throughput / price。 */
    private String sort = "latency";

    /** 允许的量化档；空 = 不限制。OpenRouter 枚举：int4 int8 fp4 mxfp4 nvfp4 fp6 fp8 mxfp8 fp16 bf16 fp32 unknown。 */
    private List<String> quantizations = new ArrayList<>();

    /** 首选供应商失败时是否允许 OpenRouter 自己换家。 */
    private boolean allowFallbacks = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSort() { return sort; }
    public void setSort(String sort) { this.sort = sort; }
    public List<String> getQuantizations() { return quantizations; }
    public void setQuantizations(List<String> quantizations) { this.quantizations = quantizations; }
    public boolean isAllowFallbacks() { return allowFallbacks; }
    public void setAllowFallbacks(boolean allowFallbacks) { this.allowFallbacks = allowFallbacks; }
}
