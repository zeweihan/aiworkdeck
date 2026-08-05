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
            throw new IllegalStateException("「" + itemName + "」是付费项目（" + yuan(priceCents)
                    + "），请先在设置的「账户与用量」中连接 AI Workdeck 账户");
        }
        return key;
    }

    /**
     * 把官网 402 {@code {code:"payment_required", priceCents, itemName}} 翻成明确的中文错误。
     * 官网字段缺失或响应体不是 JSON 时退回调用方已知的名称与价格，不吞成通用失败。
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
        String price = cents > 0 ? "（" + yuan(cents) + "）" : "";
        return new IllegalStateException("「" + name + "」需购买后安装" + price
                + "。请在官网完成购买，再点「我已购买，刷新」重试");
    }

    /** 分转元，两位小数。与官网 formatPriceYuan 同口径。 */
    public static String yuan(int cents) {
        return "¥" + BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
