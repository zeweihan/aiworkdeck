package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.NetworkRegionService;
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
 */
@RestController
@RequestMapping("/api/ai")
public class AiModelCatalogController {

    private final NetworkRegionService networkRegionService;
    private final ChatModelFactory chatModelFactory;

    public AiModelCatalogController(NetworkRegionService networkRegionService,
                                    ChatModelFactory chatModelFactory) {
        this.networkRegionService = networkRegionService;
        this.chatModelFactory = chatModelFactory;
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
            dto.put("inputPricePerM", first.inputPricePerM());
            dto.put("outputPricePerM", first.outputPricePerM());
            dto.put("tiered", m.getPriceTiers().size() > 1);
            models.add(dto);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("networkRegion", region.name());
        body.put("networkRegionMode", networkRegionService.mode());
        body.put("networkRegionBasis", networkRegionService.detectionBasis());
        // 默认模型必须由工厂解析：DB 的 ai.defaultModel 优先于 yml，
        // 前端自己挑「清单第一条」会和实际发出去的模型不一致
        body.put("defaultModel", chatModelFactory.resolveDefaultModel());
        body.put("models", models);
        return ResponseEntity.ok(body);
    }
}
