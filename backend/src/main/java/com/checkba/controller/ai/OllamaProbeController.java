package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.WizardStateService;
import com.checkba.service.ai.OllamaProbeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地 Ollama 连通性与模型探测：{@code GET /api/ai/ollama/probe}。
 *
 * <p>供应商向导的本地档（决策 2：Ollama = 离线/实验档）没有密钥可校验，
 * 只能靠探测判断「能不能用」。探测逻辑与三态口径见 {@link OllamaProbeService}。
 *
 * <p>永远返回 200 + status 字段（SERVICE_DOWN 也是一种正常结论），
 * 让向导直接按 status 渲染下一步，不需要区分「HTTP 失败」和「探测失败」。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class OllamaProbeController {

    private final OllamaProbeService ollamaProbeService;
    private final WizardStateService wizardStateService;

    /**
     * @param model 可选：探测指定模型（向导里用户还没保存设置就想先试的场景）；
     *              留空则用 system_setting 的 ai.ollama.modelName，再回退 yml 的 ai.model.ollama.model-name
     */
    @GetMapping("/ollama/probe")
    public ResponseEntity<?> probe(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "model", required = false) String model) {
        // 鉴权口径与同目录其他 AI 控制器一致（会话解析照抄 AiChatController / AiModelCatalogController）。
        // 唯一的例外：全新安装的匿名向导窗口——向导 POST 本身在这个窗口里就是匿名可达的
        // （WizardController.initialize），本地档没有密钥可校验、只能靠探测判断能不能用，
        // 探测被 401 挡住等于「本地 Ollama 这一档在向导里永远提交不了」，
        // 违反本领域的硬规则「向导里每一条下一步都必须能在向导里做完」。
        // 窗口边界与向导共用 WizardStateService 的同一个判据，不另写一份。
        // 该窗口只暴露「本机 Ollama 在不在跑、pull 了哪些模型」，且不接受调用方指定的地址
        // （baseUrl 只从配置解析），不构成 SSRF 跳板。
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null && !wizardStateService.inAnonymousSetupWindow()) {
            return ResponseEntity.status(401).body("请先登录");
        }
        return ResponseEntity.ok(ollamaProbeService.probe(model));
    }
}
