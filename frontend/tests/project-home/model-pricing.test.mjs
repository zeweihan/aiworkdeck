// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#853：模型选择器的价格显示（实付价 / 标价口径、数字格式、贵贱档位）。
//
// 判据在 src/utils/modelPricing.js；接线（两处下拉都换成共享组件、组件真的读这些函数）
// 用文件末尾的 source 断言守——判据对了而没接上去是最典型的静默失效。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  formatPriceNumber,
  formatFactor,
  formatRateDate,
  modelPriceTexts,
  normalizePriceDisplay,
  priceLevelOf,
} from '../../src/utils/modelPricing.js'

test('数字格式：小于 1 两位有效数字，小于 100 一位小数，否则取整', () => {
  assert.equal(formatPriceNumber(0.0301), '0.030')
  assert.equal(formatPriceNumber(0.753), '0.75')
  assert.equal(formatPriceNumber(0.08596), '0.086')
  assert.equal(formatPriceNumber(1.506), '1.5')
  assert.equal(formatPriceNumber(5), '5.0', '不去末尾 0：一列数字才对得齐')
  assert.equal(formatPriceNumber(26.28), '26.3')
  assert.equal(formatPriceNumber(99.94), '99.9')
  assert.equal(formatPriceNumber(131.4), '131')
  assert.equal(formatPriceNumber(1234.5), '1235')
})

test('数字格式：进位跨段时按下一段写，不出现 1.0 位于 <1 段、100.0 位于 <100 段', () => {
  assert.equal(formatPriceNumber(0.996), '1.0')
  assert.equal(formatPriceNumber(99.96), '100')
})

test('数字格式：异常值不抛、不显示成 0', () => {
  assert.equal(formatPriceNumber(0), '0')
  for (const bad of [null, undefined, '', NaN, -1, Infinity, 'abc']) {
    assert.equal(formatPriceNumber(bad), '-', String(bad))
  }
  assert.equal(formatPriceNumber(1e-7), '0.00000010', '极小值不许写成科学计数法')
})

test('口径规范化：旧后端缺 priceDisplay 时就是美元标价', () => {
  assert.deepEqual(normalizePriceDisplay(undefined), {
    basis: 'list', currency: 'USD', factor: 1, exchangeRate: null, marginMultiplier: null,
    channel: 'unknown', rateSource: '', rateUpdatedAt: '',
  })
  const charged = normalizePriceDisplay({ basis: 'charged', currency: 'CNY', factor: 8.52, channel: 'platform', rateSource: 'live', rateUpdatedAt: '2026-09-23T02:00:00Z' })
  assert.equal(charged.basis, 'charged')
  assert.equal(charged.currency, 'CNY')
  // 脚注要拆开写「汇率 × 平台系数」，乘积不能冒充汇率：两项都得透传
  const split = normalizePriceDisplay({ basis: 'charged', currency: 'CNY', factor: 8.1, exchangeRate: 6.7468, marginMultiplier: 1.2 })
  assert.equal(split.exchangeRate, 6.7468)
  assert.equal(split.marginMultiplier, 1.2)
  assert.equal(normalizePriceDisplay({ basis: 'list', exchangeRate: 7, marginMultiplier: 1.2 }).exchangeRate, null)
  // 不认识的币种 / 非正 factor：不许按实付价显示
  assert.equal(normalizePriceDisplay({ basis: 'charged', currency: 'EUR', factor: 8 }).basis, 'list')
  assert.equal(normalizePriceDisplay({ basis: 'charged', currency: 'CNY', factor: 0 }).basis, 'list')
})

test('两个数：实付价读 display*，标价读美元字段，旧后端缺 display* 也能显示', () => {
  const m = { inputPricePerM: 3, outputPricePerM: 15, displayInputPerM: 25.56, displayOutputPerM: 127.8 }
  assert.deepEqual(modelPriceTexts(m, { basis: 'charged', currency: 'CNY', factor: 8.52 }), { input: '¥25.6', output: '¥128' })
  assert.deepEqual(modelPriceTexts({ ...m, displayInputPerM: 3, displayOutputPerM: 15 }, { basis: 'list' }), { input: '$3.0', output: '$15.0' })
  assert.deepEqual(modelPriceTexts({ inputPricePerM: 0.03, outputPricePerM: 0.13 }, undefined), { input: '$0.030', output: '$0.13' })
  // 实付价口径下缺 display* 说明后端出错：显示 '-'，不拿美元数冒充人民币
  assert.deepEqual(modelPriceTexts({ inputPricePerM: 3, outputPricePerM: 15 }, { basis: 'charged', currency: 'CNY', factor: 8.52 }), { input: '-', output: '-' })
})

test('档位：只认后端下发的 1..4，缺字段不自己算', () => {
  assert.equal(priceLevelOf({ priceLevel: 1 }), 1)
  assert.equal(priceLevelOf({ priceLevel: 4 }), 4)
  for (const bad of [0, 5, 2.5, '3', null, undefined]) {
    assert.equal(priceLevelOf({ priceLevel: bad }), 0, String(bad))
  }
  assert.equal(priceLevelOf({ inputPricePerM: 3, outputPricePerM: 15 }), 0)
})

test('汇率与日期的显示', () => {
  assert.equal(formatFactor(8.52), '8.52')
  assert.equal(formatFactor(1.2), '1.20')
  assert.equal(formatFactor(0), '')
  assert.match(formatRateDate('2026-09-23T02:00:00Z'), /^2026-09-2[23]$/)
  assert.equal(formatRateDate('not-a-date'), '')
  assert.equal(formatRateDate(null), '')
})

test('接线：两处下拉都用共享组件，ChatInterface 里不再有第二份价格格式化', () => {
  const chat = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
  assert.equal((chat.match(/<ModelSelectorDropdown\b/g) || []).length, 2, '空态与常态两处都要换成共享组件')
  assert.doesNotMatch(chat, /priceLabel|formatPrice\s*=/, '价格格式化只许在 modelPricing.js 一处')
  const dropdown = readFileSync(new URL('../../src/components/ModelSelectorDropdown.vue', import.meta.url), 'utf8')
  for (const fn of ['modelPriceTexts', 'priceLevelOf', 'normalizePriceDisplay']) {
    assert.match(dropdown, new RegExp(fn), `下拉组件没用 ${fn}`)
  }
  assert.match(dropdown, /tabular-nums/, '价格列必须是等宽数字')
})

test('文案：两套语言键成对', async () => {
  const zh = (await import('../../src/locales/zh-CN/chat.js')).default
  const en = (await import('../../src/locales/en-US/chat.js')).default
  const keys = Object.keys(zh).filter(k => k.startsWith('modelPrice'))
  assert.ok(keys.length >= 8, `价格文案键太少: ${keys}`)
  for (const k of keys) assert.ok(typeof en[k] === 'string' && en[k], `en-US 缺 ${k}`)
})
