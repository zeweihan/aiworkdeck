// 广场付费项的展示口径（商业化改造 PR-D）。
// 左栏列表 MarketSidebarPanel 与中栏详情 MarketDetailPane 两处共用，避免状态判定与文案各写一套。
//
// 契约：官网 registry 列表带 priceCents（分，0 = 免费）与 pricingModel。
// 官网旧格式没有这两个字段——后端已归一为 0 / 'once'，这里再兜一次底：
// **字段缺失一律按免费**，绝不能因为跑在旧后端/旧 registry 上就把免费项锁住。

const SITE_BASE = 'https://www.aiworkdeck.com'

/** 售价（分）。缺失、非数字、负数一律 0 = 免费。 */
export function priceCentsOf(item) {
  const n = Number(item && item.priceCents)
  return Number.isFinite(n) && n > 0 ? Math.round(n) : 0
}

export function isPaid(item) {
  return priceCentsOf(item) > 0
}

/** 分转元，两位小数。与官网 formatPriceYuan 同口径。 */
export function formatPrice(cents) {
  return '¥' + (Number(cents) / 100).toFixed(2)
}

/** 价格标签：免费项「免费」，已购项「已购买」，其余「¥xx.xx」。 */
export function priceLabel(item) {
  const cents = priceCentsOf(item)
  if (!cents) return '免费'
  if (item && item.purchased) return '已购买'
  return formatPrice(cents)
}

/**
 * 条目的付费状态，决定按钮形态：
 * - 'free'         免费项 —— 安装流程一字不变，不多一步
 * - 'purchased'    付费项已购 —— 与免费项同样直接安装
 * - 'buy'          付费项未购、账户已连接 —— 去官网商品页购买
 * - 'need-account' 付费项未购、账户未连接 —— 先去设置连接账户
 */
export function paidState(item, accountConnected) {
  if (!isPaid(item)) return 'free'
  if (item && item.purchased) return 'purchased'
  return accountConnected ? 'buy' : 'need-account'
}

/** 该状态下能否直接走安装（免费 / 已购）。 */
export function canInstall(state) {
  return state === 'free' || state === 'purchased'
}

/**
 * 官网商品页。
 *
 * Skill 有 /zh/skills/{id} 独立详情页（页内带购买按钮）；
 * 插件官网只有列表页 /zh/plugins（购买按钮在卡片上），**没有 /zh/plugins/{id} 路由**——
 * registry 里那个 homepage 默认值指向的路径并不存在，拿它当购买入口会打到 404。
 */
export function purchaseUrl(kind, id) {
  return kind === 'plugin'
    ? `${SITE_BASE}/zh/plugins`
    : `${SITE_BASE}/zh/skills/${encodeURIComponent(id)}`
}
