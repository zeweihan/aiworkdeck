package com.checkba.service.market;

import cn.hutool.json.JSONUtil;
import com.checkba.service.account.AccountService;
import com.checkba.service.entitlement.EntitlementService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 广场付费项的共用闸门（商业化改造 PR-D）。Skill 广场与插件广场两条安装链路逐字共用这一份判定，
 * 避免两边的中文提示与 feature 命名各写一套。
 *
 * <h3>feature 命名空间</h3>
 * 官网 {@code /api/account/entitlements} 返回的 feature 形如 {@code skill:<id>} / {@code plugin:<id>}，
 * 与本地功能键（{@code clipboard.unlimited} 等 {@code FeatureCatalog} 常量）在同一个列表里但语义不同。
 * 这里只用 {@link #skillFeature} / {@link #pluginFeature} 构造，**绝不**把广场条目 id 直接当 feature 查，
 * 否则一个 id 叫 {@code clipboard.unlimited} 的 Skill 就能蹭到本地 SKU 的权益。
 *
 * <h3>三层判定</h3>
 * <ol>
 *   <li>免费项（priceCents &lt;= 0，含官网旧格式缺字段）：完全不参与本闸门，链路一字不变；</li>
 *   <li>付费项 + 未连接账户：本地直接拒绝，不发无意义的请求（官网必 402）；</li>
 *   <li>付费项 + 已连接：带 Bearer 发请求，官网返回 402 时由 {@link #paymentRequired} 翻成明确中文。</li>
 * </ol>
 * 本地权益缓存只用于 UI 标注（{@link #purchased}），**不作安装前置条件**——真正的闸门在官网侧，
 * 缓存陈旧时按已购乐观放行、由 402 兜底，比在本地拦住一个真已购的用户更不容易出错。
 */
@Service
public class MarketPurchaseGate {

    private final AccountService accountService;
    private final EntitlementService entitlementService;

    public MarketPurchaseGate(AccountService accountService, EntitlementService entitlementService) {
        this.accountService = accountService;
        this.entitlementService = entitlementService;
    }

    public static String skillFeature(String id) {
        return "skill:" + id;
    }

    public static String pluginFeature(String id) {
        return "plugin:" + id;
    }

    /** 售价上限（分）= ¥100,000。超过必是注册表畸形值（如 long 被截成 int），按未知处理而不是展示假价。 */
    private static final int MAX_PRICE_CENTS = 100_000_00;

    /**
     * 注册表价格归一：旧格式缺字段、负数、明显畸形的值一律按免费。
     *
     * 「畸形按免费」不是白嫖口子——真付费项官网仍会 402 兜底；反过来「畸形按付费」会把
     * 一个跑在旧 registry 上的免费项锁死，那才是真事故。
     */
    public static int normalizePrice(Integer raw) {
        return raw == null || raw < 0 || raw > MAX_PRICE_CENTS ? 0 : raw;
    }

    /** 广场列表响应里带回前端：未连接时付费项显示「需连接账户」而不是「购买」。 */
    public boolean accountConnected() {
        return accountService.isConnected();
    }

    /** 该条目是否已购（账户权益缓存口径，仅用于列表/详情的标注）。 */
    public boolean purchased(String feature) {
        return entitlementService.isEnabled(feature);
    }

    /**
     * 安装前置：返回本次请求要带的账户 Key。
     *
     * @param priceCents 广场元数据里的价格（分）；&lt;= 0 一律按免费处理
     * @param itemName   用于错误文案的条目名
     * @return 付费项返回账户 Key；**免费项返回 null**（不带鉴权头，行为与改造前一致）
     * @throws IllegalStateException 付费项但本机尚未连接账户
     */
    public String bearerFor(int priceCents, String itemName) {
        if (priceCents <= 0) {
            return null;
        }
        String key = accountService.currentKeyOrNull();
        if (key == null) {
            throw new IllegalStateException(needAccountMessage(itemName, priceCents));
        }
        return key;
    }

    /**
     * 价格未确认时（安装前那次注册表列表查询失败）本次请求要带的 Key。
     *
     * 这一步分不清「真免费」与「付费但价格没查到」。不带 Key 的话后者官网必 402，
     * 一个真已购的用户会被反过来告知没付费；而免费项的 bundle/file 端点根本不看
     * Authorization。所以本机有 Key 就一律附上：对免费项无副作用，对付费项救回一次误报。
     *
     * @return 已连接账户返回 Key，未连接返回 null（**不抛异常**——价格未知不该把免费项拦住）
     */
    public String bearerForUnknownPrice() {
        return accountService.currentKeyOrNull();
    }

    /**
     * 「付费项 + 本机未连账户」的统一文案。
     *
     * 措辞刻意避开「请先」「登录」「未授权」三个子串：前端 services/api.js 对 {@code code:1}
     * 的消息做子串匹配来识别掉线，命中就会清本地会话、并在浏览器端跳登录页。
     * 这是一条业务错误，不是掉线。
     */
    private static String needAccountMessage(String itemName, int priceCents) {
        String price = priceCents > 0 ? "（" + yuan(priceCents) + "）" : "";
        return "「" + itemName + "」是付费项目" + price
                + "，需在设置的「账户与用量」中连接 AI Workdeck 账户后才能安装";
    }

    /**
     * 把官网 402 {@code {code:"payment_required", priceCents, itemName}} 翻成明确的中文错误。
     * 官网字段缺失或响应体不是 JSON 时退回调用方已知的名称与价格，不吞成通用失败。
     *
     * 本机未连账户时**不说「去购买」**：官网无从查这台机器的购买记录，402 只说明没带 Key，
     * 不说明用户没买过。这条分支只有价格未知的降级路径能走到（价格已知时 {@link #bearerFor} 已本地拦下）。
     */
    public IllegalStateException paymentRequired(String responseBody, String fallbackName, int fallbackPriceCents) {
        String name = fallbackName;
        int cents = fallbackPriceCents;
        try {
            var json = JSONUtil.parseObj(responseBody);
            String remoteName = json.getStr("itemName");
            if (remoteName != null && !remoteName.isBlank()) name = remoteName;
            Integer remotePrice = json.getInt("priceCents");
            if (remotePrice != null && remotePrice > 0) cents = remotePrice;
        } catch (Exception ignored) {
            // 官网没按契约返回 JSON：用本地已知信息把话说清楚即可
        }
        if (!accountService.isConnected()) {
            return new IllegalStateException(needAccountMessage(name, cents));
        }
        String price = cents > 0 ? "（" + yuan(cents) + "）" : "";
        return new IllegalStateException("「" + name + "」需购买后安装" + price
                + "。请在官网完成购买，再点「我已购买，刷新」重试");
    }

    /** 分转元，两位小数。与官网 formatPriceYuan 同口径。 */
    public static String yuan(int cents) {
        return "¥" + BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
