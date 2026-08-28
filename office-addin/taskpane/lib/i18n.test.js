/**
 * i18n.js 回归用例（dev-board#150）：
 *   node --test office-addin/taskpane/lib/i18n.test.js
 *
 * 覆盖三件事：
 *   1. ZH/EN 两本字典的 key 集合必须完全一致——缺一个就说明只翻了一半，转红比上线后
 *      发现某个字符串没有英文翻译（或反过来）更早暴露问题。
 *   2. t() 的插值（{name} 占位替换）按预期工作，未知 key 原样回退成 key 本身。
 *   3. 全仓静态断言：App.vue / ChatView.vue / SettingsView.vue 的 <template> 段内，
 *      不再有裸露的中文字面量——扫描逐行找 [一-鿿]，命中的行必须落在注释里，
 *      否则判定「漏翻」并把行号列出来转红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ZH, EN, t, currentLang } from './i18n.js'

const here = path.dirname(fileURLToPath(import.meta.url))
// t() 查的是按 currentLang 选定的那本字典（在 node --test 环境里没有 navigator/Office，
// 会落到 detectLang() 的默认分支=en）；测插值/回退行为要对着「当前生效的字典」断言，
// 不能硬编 ZH——环境不同 currentLang 会不同，但 t() 的行为契约（占位替换/未知 key 回退）
// 与语言无关，用 DICT 断言即可覆盖两种环境。
const DICT = currentLang === 'zh' ? ZH : EN

test('ZH 与 EN 字典的 key 集合完全一致', () => {
  const zhKeys = Object.keys(ZH).sort()
  const enKeys = Object.keys(EN).sort()
  const onlyInZh = zhKeys.filter((k) => !Object.prototype.hasOwnProperty.call(EN, k))
  const onlyInEn = enKeys.filter((k) => !Object.prototype.hasOwnProperty.call(ZH, k))
  assert.deepEqual(onlyInZh, [], 'ZH 独有、EN 缺失的 key: ' + onlyInZh.join(', '))
  assert.deepEqual(onlyInEn, [], 'EN 独有、ZH 缺失的 key: ' + onlyInEn.join(', '))
})

test('t() 插值：{name} 占位按 params 替换', () => {
  assert.equal(t('currentProjectTitle', { name: 'X' }), DICT.currentProjectTitle.replace('{name}', 'X'))
  assert.equal(t('connectSuccessWithProjects', { count: 3 }),
    DICT.connectSuccessWithProjects.replace('{count}', '3'))
})

test('t() 插值：缺失的参数保留占位原样', () => {
  assert.equal(t('currentProjectTitle', {}), DICT.currentProjectTitle)
})

test('t() 未知 key 回退为 key 本身', () => {
  assert.equal(t('__not_a_real_key__'), '__not_a_real_key__')
  assert.equal(t('__not_a_real_key__', { name: 'x' }), '__not_a_real_key__')
})

test('t() 不传 params 时原样返回字典值（不做占位替换）', () => {
  assert.equal(t('send'), DICT.send)
})

// ==================== 语言判定：Office.context.displayLanguage 优先，退回 navigator.language ====================
// 语言只在模块加载时算一次，要覆盖多个分支就得绕开 ESM 的模块缓存——每个用例
// 用带独立查询串的动态 import 拿到一份「刚加载」的模块实例（与 settings.test.js
// 用 stub globalThis 的思路一致，这里额外需要 cache-busting）。

/**
 * 覆盖一个全局标识符并返回恢复函数。不能直接赋值——Node 21+ 的 `navigator`
 * 是内置的只读 getter（globalThis.navigator = x 会抛 TypeError），统一用
 * defineProperty 覆盖，对 Office 这种普通属性同样适用。
 */
function stubGlobal(name, value) {
  const had = Object.prototype.hasOwnProperty.call(globalThis, name)
  const original = had ? Object.getOwnPropertyDescriptor(globalThis, name) : null
  Object.defineProperty(globalThis, name, {
    value, configurable: true, writable: true, enumerable: true
  })
  return () => {
    if (had) Object.defineProperty(globalThis, name, original)
    else delete globalThis[name]
  }
}

let importSeq = 0
async function freshImport() {
  importSeq += 1
  return import(`./i18n.js?case=${importSeq}`)
}

test('语言判定：Office.context.displayLanguage=zh-CN 时判为中文', async () => {
  const restoreOffice = stubGlobal('Office', { context: { displayLanguage: 'zh-CN' } })
  try {
    const mod = await freshImport()
    assert.equal(mod.currentLang, 'zh')
  } finally {
    restoreOffice()
  }
})

test('语言判定：Office.context.displayLanguage=en-US 时判为英文', async () => {
  const restoreOffice = stubGlobal('Office', { context: { displayLanguage: 'en-US' } })
  try {
    const mod = await freshImport()
    assert.equal(mod.currentLang, 'en')
  } finally {
    restoreOffice()
  }
})

test('语言判定：Office 未定义时退回 navigator.language（zh-TW 判中文）', async () => {
  const restoreOffice = stubGlobal('Office', undefined)
  const restoreNav = stubGlobal('navigator', { language: 'zh-TW' })
  try {
    const mod = await freshImport()
    assert.equal(mod.currentLang, 'zh')
  } finally {
    restoreOffice()
    restoreNav()
  }
})

test('语言判定：Office.context 读取抛异常时不炸，退回 navigator.language', async () => {
  const restoreOffice = stubGlobal('Office', {
    get context() { throw new Error('Office 未就绪') }
  })
  const restoreNav = stubGlobal('navigator', { language: 'en-GB' })
  try {
    const mod = await freshImport()
    assert.equal(mod.currentLang, 'en')
  } finally {
    restoreOffice()
    restoreNav()
  }
})

test('语言判定：Office 与 navigator 都拿不到语言时默认英文', async () => {
  const restoreOffice = stubGlobal('Office', undefined)
  const restoreNav = stubGlobal('navigator', undefined)
  try {
    const mod = await freshImport()
    assert.equal(mod.currentLang, 'en')
  } finally {
    restoreOffice()
    restoreNav()
  }
})

// ==================== 静态断言：模板段不许有裸中文 ====================

const SCAN_FILES = [
  '../App.vue',
  '../components/ChatView.vue',
  '../components/SettingsView.vue',
  '../components/TransferPanel.vue'
]

const HAN_RE = /[一-鿿]/

/**
 * 抠出 <template>...</template> 区间，把 HTML 注释 <!-- ... --> 的内容整体
 * 替换成空格（保留换行，行号不跑偏），剩下的逐行扫——命中中文字符即判定裸露。
 */
function findBareChineseLines(content) {
  const startTag = '<template>'
  const endTag = '</template>'
  const start = content.indexOf(startTag)
  const end = content.lastIndexOf(endTag)
  assert.ok(start >= 0 && end > start, '未找到 <template> 区间')
  const templateBlock = content.slice(start, end)
  const startLine = content.slice(0, start).split('\n').length

  const stripped = templateBlock.replace(/<!--[\s\S]*?-->/g, (m) =>
    m.replace(/[^\n]/g, ' '))

  const offenders = []
  stripped.split('\n').forEach((line, i) => {
    if (HAN_RE.test(line)) {
      offenders.push({ line: startLine + i, text: line.trim() })
    }
  })
  return offenders
}

for (const rel of SCAN_FILES) {
  test(`${rel} 的 template 段内没有裸中文（须全部走 t()）`, () => {
    const filePath = path.join(here, rel)
    const content = fs.readFileSync(filePath, 'utf8')
    const offenders = findBareChineseLines(content)
    assert.deepEqual(offenders, [],
      `${rel} 发现裸中文，行号: ` + offenders.map((o) => `${o.line}: ${o.text}`).join(' | '))
  })
}
