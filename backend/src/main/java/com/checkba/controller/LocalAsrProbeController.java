package com.checkba.controller;

import com.checkba.service.WizardStateService;
import com.checkba.service.meeting.LocalAsrClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机转写就绪探测：{@code GET /api/asr/local/probe}。
 *
 * <p>「录音不出本机」这个开关没有密钥可校验，只能靠探测判断能不能用。
 * 而「服务没起」与「模型没下」的下一步完全不同——前者重启应用，后者要下一个 GB 级的模型，
 * 合并成一句「不可用」等于让律师在按下录音键之后才发现自己没有出路。
 *
 * <p>永远返回 200 + status 字段（SERVICE_DOWN 也是一种正常结论），
 * 界面直接按 status 渲染下一步。
 */
@RestController
@RequiredArgsConstructor
public class LocalAsrProbeController {

    private final LocalAsrClient localAsrClient;
    private final WizardStateService wizardStateService;

    @GetMapping("/api/asr/local/probe")
    public ResponseEntity<?> probe(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 匿名窗口的口径与 OllamaProbeController 逐字相同，判据也共用 WizardStateService
        // 一处：全新安装走向导时还没有任何会话，探测被 401 挡住等于「本地档在向导里
        // 永远选不了」，违反「向导里每一条下一步都必须能在向导里做完」。
        // 本端点只暴露「本机 asr-service 在不在跑、模型下没下」，且不接受调用方指定地址
        // （baseUrl 只从配置解析），不构成 SSRF 跳板。
        // local-mode 下 getUserIdFromSession 恒解析出本机用户，这条分支只对团队服务器生效。
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null && !wizardStateService.inAnonymousSetupWindow()) {
            return ResponseEntity.status(401).body("请先登录");
        }
        return ResponseEntity.ok(localAsrClient.probe());
    }
}
