// team 命名空间的文案红线（dev-board#496）：两语言键集合一致、禁 emoji、
// 中文不许含 api.js 历史上用来判掉线的三个子串。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const load = async (lang) =>
  (await import(resolve(root, `src/locales/${lang}/team.js`))).default

const flatten = (obj, prefix = '') =>
  Object.entries(obj).flatMap(([k, v]) => {
    const full = prefix ? `${prefix}.${k}` : k
    return v && typeof v === 'object' ? flatten(v, full) : [full]
  })

const values = (obj) =>
  Object.values(obj).flatMap((v) => (v && typeof v === 'object' ? values(v) : [String(v)]))

test('zh-CN / en-US 的 team 命名空间键集合完全一致', async () => {
  const zh = flatten(await load('zh-CN')).sort()
  const en = flatten(await load('en-US')).sort()
  assert.deepEqual(zh, en)
})

test('中文文案不含「登录」「未授权」「请先」——api.js 曾拿这三个子串判掉线并清会话', async () => {
  const zh = await load('zh-CN')
  for (const [key, value] of Object.entries(zh)) {
    for (const needle of ['登录', '未授权', '请先']) {
      assert.ok(!String(value).includes(needle), `team.${key} 含掉线子串「${needle}」：${value}`)
    }
  }
})

test('两语言都不含 emoji（全站红线）', async () => {
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/u
  for (const lang of ['zh-CN', 'en-US']) {
    for (const value of values(await load(lang))) {
      assert.ok(!emoji.test(value), `${lang} 文案含 emoji：${value}`)
    }
  }
})

test('「节约时间」的中英文案都带「估算 / estimated」，不做成看起来精确的数字', async () => {
  const zh = await load('zh-CN')
  const en = await load('en-US')
  assert.ok(zh.kpiSavedMinutes.includes('估算'), zh.kpiSavedMinutes)
  assert.ok(/estimat/i.test(en.kpiSavedMinutes), en.kpiSavedMinutes)
  assert.ok(zh.savedFormula.includes('分钟'), '中文脚注必须把公式摆出来')
})

test('隐私一句话点明「不含文档内容/文件名/对话」', async () => {
  const zh = await load('zh-CN')
  for (const needle of ['文档内容', '文件名', '对话']) {
    assert.ok(zh.privacyLine.includes(needle), `隐私文案缺「${needle}」：${zh.privacyLine}`)
  }
})
