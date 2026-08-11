package com.checkba.controller;

import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformUsageAccountant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 与官网账户的连接（商业化改造 PR-B）。
 *
 * <ul>
 *   <li>GET  /api/account/status     当前连接状态（不含 Key 明文）</li>
 *   <li>POST /api/account/connect    {"key":"awdk_..."} 校验并落盘</li>
 *   <li>POST /api/account/disconnect 断开并清空权益缓存、平台 AI 密钥缓存</li>
 *   <li>GET  /api/account/usage      平台结算（官网）+ 本地统计（TokenUsage）两套口径</li>
 * </ul>
 *
 * 鉴权与全站同一条：先过 {@link AuthController#getUserIdFromSession}。local-mode 下它把
 * 任何请求解析为本机用户（等于免登），server 模式（团队案件库）下无有效会话即拒绝——
 * 后者不可省：{@code LocalModeAccessFilter} 在 local-mode=false 时整体短路，
 * 而 {@code deploy/web/nginx.conf.example} 把 /api/ 整段反代出去，漏检等于把
 * 账户状态与「断开连接」暴露给匿名请求。
 *
 * local-mode 下 POST 另外还落在 PR-A 的 {@code LocalModeAccessFilter} 之内
 * （跨站 Origin 硬拦截 + 回环校验 + 反代痕迹拒绝），
 * 因此「任意网页悄悄把用户账户断开/换绑」这条路是关死的。
 *
 * 返回沿用全站信封 {@code {code:0,data:...}} / {@code {code:1,message:"中文"}}（HTTP 恒 200），
 * 与 frontend/src/services/api.js 的拦截约定一致。
 */
@RestController
@RequestMapping("/api/account")
@Slf4j
public class AccountController {

    private final AccountService accountService;
    private final PlatformAiChannel platformAiChannel;
    private final AccountSwitchCleanup accountSwitchCleanup;
    private final TokenUsageRepository tokenUsageRepository;
    private final MachineAccountGuard machineAccountGuard;

    public AccountController(AccountService accountService,
                             PlatformAiChannel platformAiChannel,
                             AccountSwitchCleanup accountSwitchCleanup,
                             TokenUsageRepository tokenUsageRepository,
                             MachineAccountGuard machineAccountGuard) {
        this.accountService = accountService;
        this.platformAiChannel = platformAiChannel;
        this.accountSwitchCleanup = accountSwitchCleanup;
        this.tokenUsageRepository = tokenUsageRepository;
        this.machineAccountGuard = machineAccountGuard;
    }

    /**
     * 身份闸（插件云后端加固后收严）：local-mode 恒放行（本机用户即机器主人）；
     * server 模式下账户连接是<b>机器级</b>状态，仅 admin 可读可改——普通租户
     * disconnect 一下全服的平台 AI 通道就断了。判定在 {@link MachineAccountGuard}。
     */
    private void requireUser(String sessionId) {
        machineAccountGuard.requireMachineScope(sessionId);
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        Map<String, Object> data = new LinkedHashMap<>(accountService.status());
        // 平台 AI 通道是否可选（未连接账户时前端不展示该供应商）
        data.put("platformAiAvailable", platformAiChannel.isAvailable());
        return ok(data);
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        String key = body == null ? null : body.get("key");
        Map<String, Object> status = accountService.connect(key);
        // 换账户后旧账户的权益、平台密钥、余额判定与用量基线必须立刻作废，不能等下一次刷新。
        // 解锁页那条连接路径共用这一处（AccountSwitchCleanup），别在这里再抄一遍动作
        accountSwitchCleanup.afterConnect();
        return ok(status);
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        Map<String, Object> status = accountService.disconnect();
        Map<String, Object> data = new LinkedHashMap<>(status);
        String fallback = accountSwitchCleanup.afterDisconnect();
        if (fallback != null) {
            // 前端据此更新设置页的供应商单选并提示用户，避免「界面显示平台通道正常选中、
            // 实际每条消息都报未连接账户」
            data.put("aiProviderFallback", fallback);
        }
        return ok(data);
    }

    /**
     * 用量：两套数字**分开标注**，不做合并（Spec §3）。
     * - local：本机 TokenUsage 汇总，BYOK 部分是按单价表估算的；
     * - platform：官网账户余额与 AI 额度分配流水，是真实结算口径。
     *
     * 官网侧不可达时只降级 platform 段，本地统计照常返回。
     */
    @GetMapping("/usage")
    public Map<String, Object> usage(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        Long userId = AuthController.getUserIdFromSession(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("local", localUsage(userId));
        data.put("platform", platformUsage());
        return ok(data);
    }

    // ==================== 内部 ====================

    /** 明细列表的条数上限：设置页只是「最近用量」，全量导出不是本端点的职责。 */
    private static final int RECENT_LIMIT = 50;

    private Map<String, Object> localUsage(Long userId) {
        List<TokenUsage> records = userId == null ? List.of() : tokenUsageRepository.findByUserId(userId);
        long prompt = 0;
        long completion = 0;
        long total = 0;
        BigDecimal platformCost = BigDecimal.ZERO;
        BigDecimal estimatedCost = BigDecimal.ZERO;
        for (TokenUsage record : records) {
            prompt += record.getPromptTokens() == null ? 0 : record.getPromptTokens();
            completion += record.getCompletionTokens() == null ? 0 : record.getCompletionTokens();
            total += record.getTotalTokens() == null ? 0 : record.getTotalTokens();
            if (record.getCost() == null) continue;
            if (PlatformUsageAccountant.SOURCE_PLATFORM.equals(record.getCostSource())) {
                platformCost = platformCost.add(record.getCost());
            } else {
                estimatedCost = estimatedCost.add(record.getCost());
            }
        }
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("records", records.size());
        local.put("promptTokens", prompt);
        local.put("completionTokens", completion);
        local.put("totalTokens", total);
        // 平台通道的实际扣费（美元），已完成对账的部分
        local.put("platformCostUsd", platformCost.toPlainString());
        // BYOK 的本地估算（美元），**不是账单**
        local.put("estimatedCostUsd", estimatedCost.toPlainString());
        local.put("recent", recentRows(records));
        return local;
    }

    /**
     * 明细行：只挑设置页要展示的字段，不直接序列化实体
     * （实体里还有 userId/projectId/conversationId，与「最近用量」无关）。
     * cost 为 null 原样保留——平台通道对账未完成时前端显示「待结算」，绝不能顶成 0。
     */
    private static List<Map<String, Object>> recentRows(List<TokenUsage> records) {
        return records.stream()
                .sorted(Comparator.comparing(TokenUsage::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_LIMIT)
                .map(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", record.getModel());
                    row.put("createdAt", record.getCreatedAt());
                    row.put("totalTokens", record.getTotalTokens());
                    row.put("cost", record.getCost());
                    row.put("costSource", record.getCostSource());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> platformUsage() {
        Map<String, Object> platform = new LinkedHashMap<>();
        if (!accountService.isConnected()) {
            platform.put("connected", false);
            return platform;
        }
        platform.put("connected", true);
        try {
            Map<String, Object> profile = accountService.fetchProfile();
            platform.put("balanceCents", profile.get("balanceCents"));
            platform.put("plan", profile.get("plan"));
            platform.put("allocations", accountService.fetchLedger().stream()
                    .filter(entry -> "ai_alloc".equals(entry.get("kind")))
                    .toList());
            platform.put("available", true);
        } catch (AccountException e) {
            // 官网不可达不该让整个用量面板报错——本地统计仍然有价值
            platform.put("available", false);
            platform.put("message", e.getMessage());
            return platform;
        }
        putAiQuota(platform);
        return platform;
    }

    /**
     * AI 额度单独一段，失败只降级这一段：余额与账本已经取到了，不该因为额度查询挂掉就一起隐藏。
     * {@code quotaAvailable=false} 时前端显示「额度信息暂不可用」，绝不把 0 当成真实剩余额度。
     *
     * Credits 重构后新增 {@code creditsCents}：这才是「能不能用平台 AI」的判据。
     * hasAiQuota 现在由它算出，不再等价于「官网库里有没有 key 行」。
     */
    private void putAiQuota(Map<String, Object> platform) {
        try {
            Map<String, Object> quota = accountService.fetchAiUsage();
            // 官网 Credits 重构后 usageAvailable=false 表示「用量查不到」，但 Credits 余额仍是可信的。
            // 缺字段时按 true 处理，兼容尚未升级的官网。
            boolean usageOk = !Boolean.FALSE.equals(quota.get("usageAvailable"));
            platform.put("quotaAvailable", usageOk);
            // 能不能用平台 AI，判据是 Credits 余额，**不是** hasKey。
            // 新账户充完值到第一次调用之间 hasKey 仍为 false，用旧判据会把可用的用户挡在门外
            // （向导里那条路当年就是这么走死的）。缺 creditsCents 时回落 hasKey 兼容旧官网。
            Object credits = quota.get("creditsCents");
            platform.put("creditsCents", credits);
            platform.put("hasAiQuota", credits instanceof Number n
                    ? n.longValue() > 0
                    : Boolean.TRUE.equals(quota.get("hasKey")));
            if (usageOk) {
                platform.put("limitUsd", quota.get("limitUsd"));
                platform.put("usageUsd", quota.get("usageUsd"));
                platform.put("remainingUsd", quota.get("remainingUsd"));
            } else {
                platform.put("quotaMessage", "用量暂时查不到，Credits 余额不受影响");
                platform.put("limitUsd", platformAiChannel.limitUsd());
            }
        } catch (AccountException e) {
            platform.put("quotaAvailable", false);
            platform.put("quotaMessage", e.getMessage());
            // 本地缓存的 limit 是 provision 当时的快照，只在实时口径拿不到时兜底展示
            platform.put("limitUsd", platformAiChannel.limitUsd());
        }
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /**
     * 账户类失败统一转成全站信封。HTTP 恒 200 + code=1，
     * 前端 api.js 会 reject 并把 message 原样弹给用户（中文，且不含 Key 明文）。
     */
    @ExceptionHandler(AccountException.class)
    public ResponseEntity<Map<String, Object>> handleAccountException(AccountException e) {
        log.warn("账户操作失败 [{}]: {}", e.getKind(), e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("kind", e.getKind().name());
        result.put("message", e.getMessage());
        return ResponseEntity.ok(result);
    }
}
