package com.checkba.controller;

import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountSwitchCleanup;
import com.checkba.service.account.MachineAccountGuard;
import com.checkba.service.account.SkuPurchaseException;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.LangText;
import com.checkba.service.ai.PlatformUsageAccountant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 与官网账户的连接（商业化改造 PR-B）。
 *
 * <ul>
 *   <li>GET  /api/account/status     当前连接状态（不含 Key 明文）</li>
 *   <li>POST /api/account/connect    {"key":"awdk_..."} 校验并落盘</li>
 *   <li>POST /api/account/disconnect 断开并清空权益缓存、平台 AI 密钥缓存</li>
 *   <li>GET  /api/account/usage      平台结算（官网）+ 本地统计（TokenUsage）两套口径</li>
 *   <li>GET  /api/account/balance    轻端点，供顶栏高频轮询（内部带 TTL 缓存）</li>
 *   <li>GET  /api/account/membership 会员等级/积分全量转发</li>
 *   <li>POST /api/account/recharge   {"amountCents":N} 发起充值，转发官网 payment/create</li>
 *   <li>GET  /api/account/recharge/status?outTradeNo= 查询充值订单状态</li>
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
    private final EntitlementService entitlementService;

    public AccountController(AccountService accountService,
                             PlatformAiChannel platformAiChannel,
                             AccountSwitchCleanup accountSwitchCleanup,
                             TokenUsageRepository tokenUsageRepository,
                             MachineAccountGuard machineAccountGuard,
                             EntitlementService entitlementService) {
        this.accountService = accountService;
        this.platformAiChannel = platformAiChannel;
        this.accountSwitchCleanup = accountSwitchCleanup;
        this.tokenUsageRepository = tokenUsageRepository;
        this.machineAccountGuard = machineAccountGuard;
        this.entitlementService = entitlementService;
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

    /**
     * 账户登录：给手机号发验证码（转发官网）。
     *
     * 与 {@code /connect} 的关系：两条路殊途同归，都是让本机持有一枚 {@code awdk_} Key。
     * {@code /connect} 收用户手工粘贴的 Key（团队服务器与私有部署仍要用），
     * 这条与 {@code /login} 则让用户直接用手机号/邮箱登录，Key 由官网签发、本机保存，
     * 用户不再需要亲眼见到它。
     */
    @PostMapping("/login/send-code")
    public Map<String, Object> loginSendCode(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        String phone = body == null ? null : body.get("phone");
        String email = body == null ? null : body.get("email");
        String captchaToken = body == null ? null : body.get("captchaToken");
        // 按填了什么分叉，不判站点——判站点的是官网（它才知道自己有没有对应通道），
        // 本机只负责转发。与下面 login 的口径一致。
        if (email != null && !email.isBlank()) {
            accountService.sendLoginCodeByEmail(email, captchaToken);
        } else {
            accountService.sendLoginCode(phone, captchaToken);
        }
        return ok(Map.of("sent", true));
    }

    /**
     * 官网人机验证的公开配置，供桌面端渲染控件用。只有公开参数，没有密钥。
     * 未启用时官网回 {@code {"provider": null}}，桌面端据此跳过控件直接发码。
     */
    @GetMapping("/captcha-config")
    public Map<String, Object> captchaConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        return ok(accountService.captchaConfig());
    }

    /**
     * 账户登录：手机号+验证码 或 账号+口令，换 Key 并连接。
     *
     * 两种凭据形状按站点分：大陆站手机号，国际站邮箱口令。这里不判站点——
     * 判站点的是官网（它才知道自己有没有短信通道），本机按用户填了什么转发即可。
     */
    @PostMapping("/login")
    public Map<String, Object> login(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        String phone = body == null ? null : body.get("phone");
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        // 三种凭据形状：手机号+码（cn）、邮箱+码（intl）、账号+口令（两站的存量口令账号）。
        // 邮箱那条不是可选项——intl 的验证码注册建出来的账号没有口令，
        // 少了它新用户在官网注册完就连不上桌面端。
        Map<String, Object> status;
        if (phone != null && !phone.isBlank()) {
            status = accountService.loginWithPhone(phone, code);
        } else if (email != null && !email.isBlank()) {
            status = accountService.loginWithEmailCode(email, code);
        } else {
            status = accountService.loginWithPassword(
                    body == null ? null : body.get("account"),
                    body == null ? null : body.get("password"));
        }
        // 与 /connect 同一条：换账户后旧账户的权益、平台密钥、余额判定与用量基线立刻作废
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

    // ==================== 会员与充值（dev-board#183/#184，桌面内嵌余额展示与充值） ====================

    /**
     * 轻端点：供顶栏高频轮询。数据源 {@link AccountService#balanceSnapshot()}，
     * 内部带 TTL 缓存（profile 60 秒、membership 摘要 10 分钟），不会每次心跳都打两次官网。
     */
    @GetMapping("/balance")
    public Map<String, Object> balance(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        return ok(accountService.balanceSnapshot());
    }

    /** 全量转发官网会员等级/积分接口，不做字段裁剪。 */
    @GetMapping("/membership")
    public Map<String, Object> membership(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        return ok(accountService.fetchMembership());
    }

    /** 单笔充值金额上限：1000000 分 = 1 万元，与官网 ORDER_MAX_CENTS 同。 */
    private static final long RECHARGE_MAX_CENTS = 1_000_000L;

    /**
     * 发起充值：body {@code {amountCents}}。idempotencyKey 由本端点生成（UUID），
     * 每次点击「充值」都是一笔新订单。响应透传官网 present/codeUrl/qrCode/redirectUrl/
     * outTradeNo/amount——微信站二维码、Stripe 站跳转链接，桌面端不在这里分叉。
     *
     * <p>参数校验失败一律 {@link IllegalArgumentException}（业务错误，code=1，绝不 4xx/4010）。
     */
    @PostMapping("/recharge")
    public Map<String, Object> recharge(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        long amountCents = parseAmountCents(body == null ? null : body.get("amountCents"));
        String idempotencyKey = UUID.randomUUID().toString();
        return ok(accountService.createRecharge(amountCents, idempotencyKey));
    }

    /** 查询充值订单状态：转发官网 payment/query，字段以官网为准，原样透传。 */
    @GetMapping("/recharge/status")
    public Map<String, Object> rechargeStatus(
            @RequestParam(value = "outTradeNo", required = false) String outTradeNo,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        if (outTradeNo == null || outTradeNo.isBlank()) {
            throw new IllegalArgumentException("outTradeNo 不能为空");
        }
        return ok(accountService.queryRecharge(outTradeNo));
    }

    /**
     * 应用内可购的本地 SKU 白名单（dev-board#187）。只收这两个：
     * 市场付费项（skill:/plugin:）仍走既有 MarketPurchaseGate 的购买链路，不从这里绕。
     * 官网 SKU id 形如 {@code feature:<FeatureCatalog 常量>}。
     */
    private static final java.util.Set<String> PURCHASABLE_SKUS = java.util.Set.of(
            "feature:clipboard.unlimited",
            "feature:stage.unlimited");

    /**
     * 应用内购买本地 SKU：body {@code {skuId}}，转发官网 POST /api/account/purchase。
     * 成功后<b>同步</b>刷新权益（与 GET /api/entitlements?refresh=true 同一条
     * {@link EntitlementService#refreshQuietly()} 路），并作废余额缓存——
     * 前端点完「解锁」立刻重查权益/余额，两者都必须是新值。
     * 白名单外的 skuId 与官网 4xx 一律 code=1 业务信封，绝不 4xx/4010。
     */
    @PostMapping("/purchase-sku")
    public Map<String, Object> purchaseSku(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireUser(sessionId);
        String skuId = body == null ? null : body.get("skuId");
        if (skuId == null || !PURCHASABLE_SKUS.contains(skuId)) {
            // 文案不含「登录/未授权/请先」，不会被 api.js 误判成掉线
            throw new IllegalArgumentException("无效商品：该功能不支持应用内购买");
        }
        Map<String, Object> purchased = accountService.purchaseSku(skuId);
        // 同步刷新：refreshQuietly 失败静默（权益下次陈旧刷新会兜住），不吞掉已成功的购买
        entitlementService.refreshQuietly();
        accountService.clearBalanceCache();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("feature", purchased.get("feature"));
        data.put("balanceCents", purchased.get("balanceCents"));
        return ok(data);
    }

    /**
     * amountCents 必须是正整数且不超过 {@link #RECHARGE_MAX_CENTS}。
     * 用 {@code Map<String,Object>} 接体而不是带校验注解的 DTO，是为了让格式错误
     * （0/负数/超上限/非整数）走业务信封而不是 Spring 绑定失败的 4xx。
     */
    private static long parseAmountCents(Object raw) {
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("充值金额必须是整数（单位：分）");
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.floor(value)) {
            throw new IllegalArgumentException("充值金额必须是整数（单位：分）");
        }
        long amountCents = number.longValue();
        if (amountCents <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0");
        }
        if (amountCents > RECHARGE_MAX_CENTS) {
            throw new IllegalArgumentException("单笔充值金额不能超过 10000 元");
        }
        return amountCents;
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
        } catch (RuntimeException e) {
            // 官网返回的 JSON 形状与约定漂移（比如 entries 里混进非对象元素）会在这里抛
            // ClassCastException 之类的运行时异常，不是 AccountException，此前接不住，
            // 冒泡出 usage() 把 data.put("local", ...) 已经算好的本地统计一起丢掉。
            // 契约漂移只应该降级 platform 这一段，处理方式与上面的 AccountException 一致。
            log.warn("platform 用量解析失败（官网返回形状与约定不符）: {}", e.toString());
            platform.put("available", false);
            platform.put("message", LangText.of("平台用量暂不可用", "Platform usage is temporarily unavailable"));
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
     * SKU 购买失败：在通用信封之上多带机器可读的 {@code reason}
     * （already_owned / insufficient_credits / invalid_sku）。「余额不足」时前端要
     * 多摆一个「去充值」按钮，靠双语 message 子串判断必然漂，reason 才是判据。
     * Spring 按异常类型就近匹配，本方法优先于下面的父类 handler。
     */
    @ExceptionHandler(SkuPurchaseException.class)
    public ResponseEntity<Map<String, Object>> handleSkuPurchaseException(SkuPurchaseException e) {
        log.warn("SKU 购买失败 [{}]: {}", e.getReason(), e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("kind", e.getKind().name());
        result.put("reason", e.getReason());
        result.put("message", e.getMessage());
        return ResponseEntity.ok(result);
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
