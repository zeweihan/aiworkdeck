// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ai.AllowedModels;
import com.checkba.config.AiModelProperties;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.ModelPriceDisplayService;
import com.checkba.service.ai.NetworkRegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录下发：{@code GET /api/ai/models}。
 *
 * <p><b>为什么单独一个控制器</b>：{@link AiChatController} 已经是历史上被治理过一轮的胖控制器，
 * 模型目录与对话/历史/导出没有共享状态，塞回去只会再攒一层。
 *
 * <p><b>为什么必须有这个端点</b>：模型清单历史上有三份互不同步的副本
 * （{@link AllowedModels}、ChatInterface.vue 的硬编码数组、project-overview.vue 的死代码），
 * 结果是「后端加模型用户看不到、前端加模型被工厂静默回落默认模型」。
 * 现在唯一事实来源是 {@link AllowedModels}，前端**不许再硬编码任何模型清单**。
 *
 * <p><b>为什么要带区域</b>：{@link AllowedModels.Region#INTERNATIONAL} 的模型在境内网络会被
 * OpenRouter 返回 403 region，OpenRouter 的 API 没有任何字段能提前告知，只能靠本机信号判定
 * （见 {@link NetworkRegionService}）。清单里只放当前区域实测可用的，
 * 同时回传判定模式与依据，好让设置页解释「国际模型为什么不见了」并给出手动覆盖入口。
 *
 * <p><b>为什么每条要带 vision</b>：产品口径是「模型不支持看图就在**选定模型的时候**告诉用户」，
 * 而不是等他发完图才说。这个判断的数据只能从这里来——{@link AllowedModels} 是唯一事实来源，
 * 前端不许自建「哪些模型能看图」的表。注意这是**预览性提示**：真正生效的模型由
 * {@code ChatModelFactory.resolveEffectiveModelId} 决定（有三条静默改写路径），
 * 所以后端在组装消息时还会再判一次，两者不一致时以后端那次为准。
 */
@RestController
@RequestMapping("/api/ai")
public class AiModelCatalogController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AiModelCatalogController.class);

    private final NetworkRegionService networkRegionService;
    private final ChatModelFactory chatModelFactory;
    /** 可空：为 null 时一律按厂商美元标价显示（既有单测与不关心价格口径的调用方走这条）。 */
    private final ModelPriceDisplayService priceDisplayService;

    public AiModelCatalogController(NetworkRegionService networkRegionService,
                                    ChatModelFactory chatModelFactory) {
        this(networkRegionService, chatModelFactory, null);
    }

    @Autowired
    public AiModelCatalogController(NetworkRegionService networkRegionService,
                                    ChatModelFactory chatModelFactory,
                                    ModelPriceDisplayService priceDisplayService) {
        this.networkRegionService = networkRegionService;
        this.chatModelFactory = chatModelFactory;
        this.priceDisplayService = priceDisplayService;
    }

    @GetMapping("/models")
    public ResponseEntity<?> listModels(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 鉴权口径与同目录其他 AI 控制器一致（会话解析照抄 AiChatController）
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).body("请先登录");
        }

        AllowedModels.Region region = networkRegionService.effectiveRegion();

        // 本地 Ollama 档下 vision 位一律归一为 false（dev-board#801 K21 ⑨，审查 E-15）。
        //
        // 病灶：这个端点只按区域过滤白名单、不感知当前 provider，而
        // ChatModelFactory.effectiveModelSupportsVision 对 OLLAMA 档**恒返回 false**
        // （langchain4j-ollama 是另一套图片编组，本仓没接）。于是用户把供应商切到本地
        // Ollama 后，模型下拉里仍是云端白名单，选中一个 vision:true 的条目时前端认为
        // 「能读图」——既不弹提示也不显示 OCR 降级说明，而后端实际一律降级走 OCR。
        // 「显示与实际不一致」正是本仓治理过一轮的老毛病，判据收敛在后端这一处。
        AiModelProperties.Provider provider = null;
        try {
            provider = chatModelFactory.resolveProvider();
        } catch (Exception e) {
            // 供应商解析不出来时不改写能力位：宁可维持白名单原值，也不要凭一次异常
            // 把所有模型都标成读不了图（那是对全体云端用户的误报）
            log.warn("[Models] Provider probe failed; keeping AllowedModels vision flags as-is", e);
        }
        boolean visionDisabledByProvider = provider == AiModelProperties.Provider.OLLAMA;

        // 价格显示口径（dev-board#853）。只有平台通道才有「实付价」：那条路按官网扣费汇率 × 毛利乘数
        // 从 Credits 里扣钱；自备 Key 的用户直接按厂商美元标价付给 OpenRouter，本地 Ollama 不计费——
        // 这两档乘上平台汇率就是在给用户报一个他根本不会付的价。
        // 取不到汇率（未连接账户 / 官网不可达 / 字段缺失 / 超时）同样退回标价，绝不编造。
        ModelPriceDisplayService.ChargedRate rate = null;
        if (provider == AiModelProperties.Provider.AWD_CLOUD && priceDisplayService != null) {
            rate = priceDisplayService.currentRate();
        }
        double factor = rate != null ? rate.factor() : 1.0;

        List<Map<String, Object>> models = new ArrayList<>();
        for (AllowedModels m : AllowedModels.availableIn(region)) {
            // 价格取首档：选择器里展示的是「起步单价」，分档模型靠 tiered 让 UI 提示
            // 「长上下文单价更高」。把整张档位表下发给前端没有消费者，也会让 UI 想去自己算钱
            // （真花的钱只以平台对账为准，见 PlatformUsageAccountant）。
            AllowedModels.PriceTier first = m.getPriceTiers().get(0);

            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", m.getModelId());
            dto.put("name", m.getDisplayName());
            dto.put("vendor", m.getVendor().getDisplayName());
            dto.put("region", m.getRegion().name());
            dto.put("contextLength", m.getContextLength());
            // 视觉能力：前端在「选模型的那一刻」就据此提示「这个模型看不了图，图片会按 OCR 文本处理」。
            // 不下发这个字段，前端只能自己维护一张模型 → 支持视觉的表，正好踩回上面那条历史债。
            dto.put("vision", !visionDisabledByProvider && m.isVision());
            dto.put("inputPricePerM", first.inputPricePerM());
            dto.put("outputPricePerM", first.outputPricePerM());
            dto.put("tiered", m.getPriceTiers().size() > 1);
            // 显示口径下的首档单价（已乘好 factor）。原来那两个美元字段保留不动：
            // 旧前端还在读，且它们是「厂商标价」这个事实本身，不随口径变。
            dto.put("displayInputPerM", roundPrice(first.inputPricePerM() * factor));
            dto.put("displayOutputPerM", roundPrice(first.outputPricePerM() * factor));
            // 贵贱档位按美元综合单价算，两站一致，见 AllowedModels.PRICE_LEVEL_UPPER_BOUNDS
            dto.put("priceLevel", m.priceLevel());
            models.add(dto);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("networkRegion", region.name());
        body.put("networkRegionMode", networkRegionService.mode());
        body.put("networkRegionBasis", networkRegionService.detectionBasis());
        // 默认模型必须由工厂解析：DB 的 ai.defaultModel 优先于 yml，
        // 前端自己挑「清单第一条」会和实际发出去的模型不一致
        body.put("defaultModel", chatModelFactory.resolveDefaultModel());
        body.put("priceDisplay", priceDisplay(provider, rate));
        body.put("models", models);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code priceDisplay}：告诉前端 display* 两个数是什么口径，脚注据此说明。
     * <ul>
     *   <li>{@code basis}：{@code charged} 实付价 / {@code list} 厂商美元标价</li>
     *   <li>{@code channel}：{@code platform} / {@code byok} / {@code local} / {@code unknown}（供应商解析失败）——list 口径下脚注要说清楚
     *       「实际怎么扣钱」，三档说法不同（平台按 Credits 扣、自备 Key 按标价付给 OpenRouter、本地不计费）</li>
     *   <li>{@code currency} / {@code factor}：list 口径恒为 USD / 1</li>
     * </ul>
     */
    private static Map<String, Object> priceDisplay(AiModelProperties.Provider provider,
                                                    ModelPriceDisplayService.ChargedRate rate) {
        Map<String, Object> pd = new LinkedHashMap<>();
        pd.put("basis", rate != null ? "charged" : "list");
        String channel = "unknown";
        if (provider == AiModelProperties.Provider.AWD_CLOUD) channel = "platform";
        else if (provider == AiModelProperties.Provider.OPENROUTER) channel = "byok";
        else if (provider == AiModelProperties.Provider.OLLAMA) channel = "local";
        pd.put("channel", channel);
        pd.put("currency", rate != null ? rate.currency() : "USD");
        pd.put("factor", rate != null ? roundPrice(rate.factor()) : 1.0);
        pd.put("exchangeRate", rate != null ? rate.exchangeRate() : null);
        pd.put("marginMultiplier", rate != null ? rate.marginMultiplier() : null);
        pd.put("currencyBasis", rate != null ? rate.currencyBasis() : null);
        pd.put("rateSource", rate != null ? rate.rateSource() : null);
        pd.put("rateUpdatedAt", rate != null ? rate.rateUpdatedAt() : null);
        return pd;
    }

    /** 去掉浮点乘法的尾巴（8.76 × 0.08596 = 0.7530096000000001），保留 6 位小数足够任何显示规则。 */
    static double roundPrice(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
