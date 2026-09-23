// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 模型选择器的价格显示（dev-board#853）。
//
// 口径由后端 GET /api/ai/models 的 priceDisplay 决定，这里只做「怎么写出来」：
//   · basis='charged'：平台通道的实付价，display* 已乘好「官网扣费汇率 × 毛利乘数」，站点币种；
//   · basis='list'   ：厂商美元标价（自备 Key / 本地 / 取不到汇率），display* 等于美元标价。
// 贵贱档位 priceLevel 也由后端按美元综合单价算好下发（两站一致），前端**不许再算一份**——
// 阈值只在 AllowedModels.PRICE_LEVEL_UPPER_BOUNDS 一处。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/project-home/model-pricing.test.mjs。

export const PRICE_LEVEL_COUNT = 4

const CURRENCY_SYMBOLS = { CNY: '¥', USD: '$' }

/**
 * 单价数字的格式：小于 1 保留 2 位有效数字，小于 100 保留 1 位小数，否则取整。
 * 例：0.0301 → '0.030'，0.753 → '0.75'，1.506 → '1.5'，26.28 → '26.3'，131.4 → '131'。
 *
 * 为什么不去掉末尾的 0：下拉里是一列数字，'5.0' 与 '26.3' 同样一位小数，
 * 配合 tabular-nums 才对得齐；'5' 会让这一列参差。
 * 边界：四舍五入后进位到下一段时按下一段的规则写（0.996 → '1.0'，99.96 → '100'）。
 */
export function formatPriceNumber(value) {
  const n = Number(value)
  if (value === null || value === undefined || value === '' || !Number.isFinite(n) || n < 0) return '-'
  if (n === 0) return '0'
  if (n < 1) {
    const s = n.toPrecision(2)
    if (Number(s) < 1) return plain(s)
  }
  if (n < 100) {
    const s = n.toFixed(1)
    if (Number(s) < 100) return s
  }
  return String(Math.round(n))
}

// toPrecision 对很小的数会给出科学计数法（1e-7 → '1.0e-7'），展开成普通小数
function plain(s) {
  if (!/e/i.test(s)) return s
  const n = Number(s)
  const digits = Math.min(20, Math.max(0, 1 - Math.floor(Math.log10(n))))
  return n.toFixed(digits)
}

export function currencySymbol(currency) {
  return CURRENCY_SYMBOLS[currency] || '$'
}

/**
 * 规范化后端的 priceDisplay。旧后端没有这个字段、或字段不合法时退回「美元标价」——
 * 那正是旧后端 inputPricePerM/outputPricePerM 的真实含义，不是猜。
 */
export function normalizePriceDisplay(raw) {
  const r = raw && typeof raw === 'object' ? raw : {}
  const charged = r.basis === 'charged' && (r.currency === 'CNY' || r.currency === 'USD')
    && Number.isFinite(Number(r.factor)) && Number(r.factor) > 0
  return {
    basis: charged ? 'charged' : 'list',
    currency: charged ? r.currency : 'USD',
    factor: charged ? Number(r.factor) : 1,
    // 脚注要把 factor 拆开说清楚（汇率 × 平台系数），不能把乘积叫「汇率」——
    // 用户会以为官方汇率是 8.1。缺任何一项时脚注退回只报 factor。
    exchangeRate: charged && Number(r.exchangeRate) > 0 ? Number(r.exchangeRate) : null,
    marginMultiplier: charged && Number(r.marginMultiplier) > 0 ? Number(r.marginMultiplier) : null,
    channel: ['platform', 'byok', 'local'].includes(r.channel) ? r.channel : 'unknown',
    rateSource: charged && ['live', 'manual', 'default'].includes(r.rateSource) ? r.rateSource : '',
    rateUpdatedAt: charged && typeof r.rateUpdatedAt === 'string' ? r.rateUpdatedAt : '',
  }
}

/**
 * 某个模型要显示的两个数（已带币种符号）。口径不是 charged 时一律用美元标价字段：
 * 旧后端没有 display*，而标价口径下两者本来相等。
 */
export function modelPriceTexts(model, display) {
  const d = display && display.basis ? display : normalizePriceDisplay(display)
  const sym = currencySymbol(d.currency)
  const pick = (shown, list) => {
    const v = d.basis === 'charged' ? shown : (isNum(shown) ? shown : list)
    return isNum(v) ? sym + formatPriceNumber(v) : '-'
  }
  return {
    input: pick(model?.displayInputPerM, model?.inputPricePerM),
    output: pick(model?.displayOutputPerM, model?.outputPricePerM),
  }
}

/** 后端下发的档位；缺字段或越界返回 0（不分档渲染，而不是自己算一份）。 */
export function priceLevelOf(model) {
  const v = model?.priceLevel
  return Number.isInteger(v) && v >= 1 && v <= PRICE_LEVEL_COUNT ? v : 0
}

/** 汇率更新时间 → 'YYYY-MM-DD'（本地时区）；解析不了返回空串，脚注里就不写这半句。 */
export function formatRateDate(iso) {
  if (!iso || typeof iso !== 'string') return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 折算系数的显示：两位小数（8.52、1.20）。它是「1 美元标价折多少站点币种」，
 * 用户拿它去对官网账单，位数少了对不上。
 */
export function formatFactor(factor) {
  const n = Number(factor)
  return Number.isFinite(n) && n > 0 ? n.toFixed(2) : ''
}

function isNum(v) {
  return typeof v === 'number' && Number.isFinite(v)
}
