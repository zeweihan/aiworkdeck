// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 落了新的一版之后，编辑器顶上那条溯源要重新问一次（真机反馈 A2 / C5）。
 *
 * 病灶：溯源只在「文档装载完 / 自动保存成功 / 宿主重载」三个时刻拉。而「结束本次
 * 工作」一个字节都不改磁盘，走不到重载链，也不会再触发自动保存——于是那条小条一直
 * 写着「初始版本」或某笔自动存档的标题，刚敲过的段落一直挂着「本机未保存的改动」，
 * 律师再改一次才刷新。
 *
 * 这里验两件事：VersionPanel 在结束工作之后真的报了信（version-landed），以及
 * 页面收到之后真的逐实例去重问了一次溯源。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

// ---- VersionPanel：结束工作之后要报 version-landed ------------------------

/** 读 .vue 的 <script>、剥掉 import、把 export default 换成 return（形制同 tests/optional-components/*）。 */
function optionsOf(rel) {
  const source = read(rel)
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const names = []
  for (const m of script.matchAll(/^import\s+([\s\S]*?)\s+from\s+'[^']+'\s*;?\s*$/gm)) {
    const clause = m[1]
    const braced = clause.match(/\{([\s\S]*?)\}/)
    if (braced) {
      for (const part of braced[1].split(',')) {
        const name = part.split(/\s+as\s+/).pop().trim()
        if (name) names.push(name)
      }
    }
    const def = clause.replace(/\{[\s\S]*?\}/, '').replace(/,/g, '').trim()
    if (def) names.push(def)
  }
  const body = script
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace(/export\s+default\s*\{/, 'return {')
  return new Function(...names, body)(...names.map(() => () => {}))
}

const panel = optionsOf('../../src/components/version/VersionPanel.vue')

test('VersionPanel 声明了 version-landed（没声明的 emit 在 uni 下是哑弹）', () => {
  assert.ok(panel.emits.includes('version-landed'))
})

test('结束本次工作：既刷新状态、也报 version-landed', () => {
  const events = []
  let refreshed = 0
  panel.methods.onVersionLanded.call({
    refresh() { refreshed++ },
    $emit: (name, payload) => events.push([name, payload]),
  })
  assert.equal(refreshed, 1)
  assert.deepEqual(events, [['version-landed', undefined]])
})

test('采纳 / 退回 / 切线这条重载链同样报 version-landed（没被改写的文件也要重问溯源）', () => {
  const events = []
  panel.methods.onReload.call({
    refresh() {},
    $emit: (name, payload) => events.push([name, payload]),
  }, [7, 9])
  assert.deepEqual(events, [['reload-files', [7, 9]], ['version-landed', undefined]])
})

test('VersionPanel 的 ended 绑在 onVersionLanded 上，页面把 version-landed 接到刷新溯源', () => {
  const panelSource = read('../../src/components/version/VersionPanel.vue')
  assert.match(panelSource, /@ended="onVersionLanded"/)
  const pageSource = read('../../src/pages/project-overview/project-overview.vue')
  assert.match(pageSource, /@version-landed="refreshLibreProvenance"/)
})

// ---- librePool：逐实例重问溯源 -------------------------------------------

function poolMethods() {
  const src = read('../../src/pages/project-overview/librePool.js')
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace(/^export\s+/gm, '')
  return new Function('isDesktopHost', src + '\nreturn librePoolMethods')(() => true)
}

test('refreshLibreProvenance 逐实例重问，跳过空白备胎与还没有这个方法的实例', () => {
  const asked = []
  const inst = {
    _libreRefs: {
      'left:1': { file: { id: 1 }, loadProvenance() { asked.push(1) } },
      // 预热备胎（file=null）：画布上是空白，没有文件可溯源
      'left:blank': { file: null, loadProvenance() { asked.push('blank') } },
      // 还在 boot / 已经 dispose：方法都还没挂上
      'right:2': { file: { id: 2 } },
      'right:3': null,
    },
  }
  Object.assign(inst, poolMethods())

  inst.refreshLibreProvenance()

  assert.deepEqual(asked, [1])
})

test('某个实例抛错不影响其它实例（溯源是锦上添花，不能连带炸掉整页）', () => {
  const asked = []
  const inst = {
    _libreRefs: {
      'left:1': { file: { id: 1 }, loadProvenance() { throw new Error('引擎没就绪') } },
      'left:2': { file: { id: 2 }, loadProvenance() { asked.push(2) } },
    },
  }
  Object.assign(inst, poolMethods())

  inst.refreshLibreProvenance()

  assert.deepEqual(asked, [2])
})
