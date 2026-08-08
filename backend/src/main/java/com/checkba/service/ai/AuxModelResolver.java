package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 辅助模型 / 子 Agent 模型的<b>模型 ID</b> 解析（2026-08 供应商三档改造）。
 *
 * <h3>为什么单列一个 Bean</h3>
 * {@link ChatModelFactory#getAuxChatModel()} 返回的是模型实例，拿不到它落在哪个模型上；
 * 而记账（{@link TokenUsageService#recordUsage}）必须带模型 ID——传 null 会落成 "default"，
 * 且 {@code AllowedModels.fromId(null)} 为空会让估算成本直接算成 0，
 * 「辅助调用不进账」的问题就只修了一半（token 数对了、钱还是 0）。
 *
 * <h3>回退链（本次改造统一口径，DB 优先于 yml）</h3>
 * <ul>
 *   <li>辅助模型：system_setting {@code ai.auxModel} → yml {@code ai.aux-model} → 白名单里最便宜那条；</li>
 *   <li>子 Agent 模型：system_setting {@code ai.subagentModel} → yml {@code ai.subagent.model}
 *       → 辅助模型。<b>默认让子 Agent 用便宜模型</b>——长程任务的子任务跟着主模型跑最烧钱。</li>
 * </ul>
 *
 * <p>刻意<b>只解析、不校验</b>：白名单校验与用户可读提示由调用方给（辅助模型走
 * {@code ChatModelFactory.getAuxChatModel()} 抛 FeatureNotConfiguredException，
 * 子 Agent 走 {@code SubAgentResult.failure} 把提示交回模型与面板），
 * 这里抛异常会把「记一笔账」变成能打断主链路的风险动作。
 *
 * <p>待收口：{@code ChatModelFactory.getAuxChatModel()} 里现在有一份同源的解析
 * （同一个 setting 键 + 同一个 yml 键）。建议让它改调本类的 {@link #auxModelId()}，
 * 彻底消掉两份口径——密钥/模型解析出现两份口径是本仓反复踩过的坑。
 */
@Component
public class AuxModelResolver {

    /** 辅助模型的 system_setting 键（起标题 / 上下文摘要 / 记忆抽取 / 深度检索 / 自动打标签） */
    public static final String SETTING_AUX_MODEL = "ai.auxModel";

    /** 子 Agent 模型的 system_setting 键 */
    public static final String SETTING_SUBAGENT_MODEL = "ai.subagentModel";

    private final SystemSettingService systemSettingService;

    /** yml 的 ai.aux-model；与 ChatModelFactory 的同名字段同源（都是白名单里最便宜的那条） */
    private final String ymlAuxModel;

    public AuxModelResolver(SystemSettingService systemSettingService,
                            @Value("${ai.aux-model:qwen/qwen3.7-flash}") String ymlAuxModel) {
        this.systemSettingService = systemSettingService;
        this.ymlAuxModel = ymlAuxModel;
    }

    /** 当前生效的辅助模型 ID（保证非空）。 */
    public String auxModelId() {
        String resolved = firstNonBlank(setting(SETTING_AUX_MODEL), ymlAuxModel);
        return resolved != null ? resolved : AllowedModels.QWEN_3_7_FLASH.getModelId();
    }

    /**
     * 当前生效的子 Agent 模型 ID（保证非空）。
     *
     * @param ymlSubAgentModel yml 的 ai.subagent.model（{@code SubAgentProperties.getModel()}），可为空
     */
    public String subAgentModelId(String ymlSubAgentModel) {
        String configured = firstNonBlank(setting(SETTING_SUBAGENT_MODEL), ymlSubAgentModel);
        return configured != null ? configured : auxModelId();
    }

    private String setting(String key) {
        return systemSettingService.get(key, null);
    }

    /**
     * 取第一个非空白候选并去掉首尾空白；全为空白返回 null。
     *
     * <p>空白必须视为「未配置」：向导/管理后台保存时未填的字段会以空串写进 DB
     * （见 ChatModelFactory.getSetting 的同款注释），直接用会把模型 ID 置空。
     */
    static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
