// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 思考型模型的思考强度（dev-board#1061）。配置前缀：ai.reasoning
 *
 * <p>OpenRouter 的统一参数 {@code "reasoning": {"effort": "..."}}。不带时供应商按自己的默认值想，
 * Kimi K3 对「这是什么文件？」这种一句话问题也会深想几十秒到几分钟，思考 token 按输出单价计费。
 *
 * <p><b>只对 {@link #models} 里点名的模型注入</b>：不是每个模型/供应商都认这个参数，
 * 名单是逐个用真实请求核对过「接受且确实变短」的；空名单 = 一个都不注入（与改动前完全一致）。
 */
@Component
@ConfigurationProperties(prefix = "ai.reasoning")
public class AiReasoningProperties {

    /** OpenRouter 文档列出的档位；配错的值一律不注入（宁可按供应商默认想，也不发一个会 400 的请求）。 */
    public static final Set<String> VALID_EFFORTS = Set.of("xhigh", "high", "medium", "low", "minimal", "none");

    /** 名单内模型默认的思考强度。 */
    private String effortDefault = "medium";

    /** 注入思考强度的模型 id 白名单（精确匹配，忽略大小写）。 */
    private List<String> models = new ArrayList<>();

    /** 模型是否在思考型模型名单里（精确匹配，忽略大小写）。供应商路由偏好也按这份名单生效。 */
    public boolean listed(String modelId) {
        if (modelId == null || models == null) return false;
        String id = modelId.trim();
        for (String m : models) {
            if (m != null && m.trim().equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    /** 该模型要注入的档位；不在名单内或档位非法时返回 null（= 不注入）。 */
    public String effortFor(String modelId) {
        if (modelId == null || models == null || models.isEmpty()) return null;
        String effort = effortDefault == null ? "" : effortDefault.trim().toLowerCase(Locale.ROOT);
        if (!VALID_EFFORTS.contains(effort)) return null;
        return listed(modelId) ? effort : null;
    }

    public String getEffortDefault() { return effortDefault; }
    public void setEffortDefault(String effortDefault) { this.effortDefault = effortDefault; }
    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }
}
