// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * BUG-64：common.dateTimeMdHm 键改成 '{year}-{month}-{day} {time}' 之后，
 * VersionTimeline.vue::timeOf 仍只传 {month,day,time}，真实 vue-i18n 渲染出「-9-25 06:58」。
 *
 * 这里用真实 locale 文件 + 真实 vue-i18n 渲染：
 *   1. 全仓扫 src 下所有消费 common.dateTimeMdHm 的调用点，抽出它传的参数名，
 *      逐个渲染——任何调用点漏传占位符都会渲染出残缺串而转红；
 *   2. VersionTimeline.timeOf 必须走 formatDateTime（与概览/合并审阅同源）；
 *   3. formatDateTime 在注入真实 i18n 后的输出是 yyyy-MM-dd HH:mm。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, dirname, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createI18n } from 'vue-i18n'
import zhCommon from '../../src/locales/zh-CN/common.js'
import enCommon from '../../src/locales/en-US/common.js'

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const SHAPE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/

function i18nFor(locale) {
  return createI18n({
    legacy: true,
    locale,
    fallbackLocale: 'zh-CN',
    silentTranslationWarn: true,
    silentFallbackWarn: true,
    messages: { 'zh-CN': { common: zhCommon }, 'en-US': { common: enCommon } },
  }).global
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = resolve(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|js|mjs|ts)$/.test(name) && !p.includes('/locales/')) out.push(p)
  }
  return out
}

/** 每个调用点：文件 + 紧跟键名之后的参数对象里出现的参数名。 */
function consumers() {
  const found = []
  for (const f of walk(SRC)) {
    const text = readFileSync(f, 'utf8')
    const re = /['"]common\.dateTimeMdHm['"]\s*,\s*\{([^}]*)\}/g
    let m
    while ((m = re.exec(text))) {
      const keys = m[1].split(',').map((s) => s.split(':')[0].trim()).filter(Boolean)
      found.push({ file: relative(SRC, f), keys })
    }
  }
  return found
}

const SAMPLE = { year: 2026, month: '09', day: '25', time: '06:58' }

test('所有 common.dateTimeMdHm 调用点：用真实 locale 渲染都是 yyyy-MM-dd HH:mm（不许漏传占位符）', () => {
  const list = consumers()
  assert.ok(list.length >= 1, '至少应有 projectHomeFormat.js 这一个消费者')
  for (const { file, keys } of list) {
    const params = Object.fromEntries(keys.filter((k) => k in SAMPLE).map((k) => [k, SAMPLE[k]]))
    for (const loc of ['zh-CN', 'en-US']) {
      const out = i18nFor(loc).t('common.dateTimeMdHm', params)
      assert.match(out, SHAPE, `${file} 传 {${keys.join(',')}} 在 ${loc} 下渲染成「${out}」`)
    }
  }
})

test('VersionTimeline.timeOf 走 formatDateTime，不再自己拼 dateTimeMdHm 参数', () => {
  const src = readFileSync(resolve(SRC, 'components/version/VersionTimeline.vue'), 'utf8')
  assert.match(src, /import \{ formatDateTime \} from '@\/utils\/projectHomeFormat\.js'/)
  assert.match(src, /timeOf\(v\) \{\s*return formatDateTime\(v\.when\)\s*\}/)
  assert.doesNotMatch(src, /\$t\(\s*['"]common\.dateTimeMdHm/)
})

test('formatDateTime 接上真实 i18n 后的渲染：两种语言都是 yyyy-MM-dd HH:mm', async () => {
  // projectHomeFormat.js 在 node 下动态 import('@/i18n') 失败会回落 zh 字面量；
  // 这里把它的 t 换成真实 vue-i18n，验证经过 locale 文件那一跳的真实输出。
  const src = readFileSync(resolve(SRC, 'utils/projectHomeFormat.js'), 'utf8')
  const m = src.match(/tr\('common\.dateTimeMdHm',\s*\{([^}]*)\}/)
  assert.ok(m, 'formatDateTime 应通过 tr(common.dateTimeMdHm, {...}) 出字')
  const keys = m[1].split(',').map((s) => s.trim())
  assert.deepEqual([...keys].sort(), ['day', 'month', 'time', 'year'])
  const { formatDateTime } = await import('../../src/utils/projectHomeFormat.js')
  const fallback = formatDateTime('2026-09-25T06:58:00')
  assert.equal(fallback, '2026-09-25 06:58')
  const [year, month, day, time] = fallback.split(/[- ]/)
  for (const loc of ['zh-CN', 'en-US']) {
    assert.equal(i18nFor(loc).t('common.dateTimeMdHm', { year, month, day, time }), fallback)
  }
})
