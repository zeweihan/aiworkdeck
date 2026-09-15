// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 版本操作之后「重载打开中的编辑器」这条链，在**非活动**标签上到底有没有生效
 * （真机反馈 A1：采纳一稿逐处裁决完，文档标签里仍是合并前的正文，关掉重开才对）。
 *
 * 病灶：非活动实例靠「摘出 libreLruKeys 让它卸载」来刷新，可**过继备胎**渲染自
 * libreSpares，而 leftLibreFiles 会把"有备胎顶着"的文件整个排除掉——只摘 LRU 键
 * 那个实例压根不卸载，端着的还是改前的字节。左窗格首开的那份文档一定是过继备胎，
 * 也就是最常见的那一种实例。
 *
 * 这里把 librePool.js 与 agentClientActions.js 的 methods 装到一个假页面实例上真跑
 * handleEditorReloadFile（源码读进来剥掉 import，形制同 tests/optional-components/*）。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')
const strip = (src) => src
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  .replace(/^export\s+/gm, '')

function loadMethods(getFileDetail) {
  const body = `
    ${strip(read('../../src/pages/project-overview/librePool.js'))}
    ${strip(read('../../src/pages/project-overview/agentClientActions.js'))}
    return { librePoolMethods, agentClientActionMethods }
  `
  // 被剥掉的 import 里，这条链真正会碰到的只有 getFileDetail；其余给个不会被调用的桩。
  const stubNames = ['isDesktopHost', 'sendEditorResult', 'createSerialQueue',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction']
  const fn = new Function('getFileDetail', ...stubNames, body)
  return fn(getFileDetail, ...stubNames.map(() => () => {}))
}

const DOC = { id: 501, name: '采购合同.docx', wpsFileId: 'w-501' }
const MERGE_TAB_ID = 'merge-review_1_采购合同.docx'

globalThis.uni = { showToast() {}, $emit() {}, $on() {}, $off() {} }

function page({ activeFileIdLeft, spareHoldsDoc, reloadResult = true }) {
  const calls = { reloaded: [] }
  const { librePoolMethods, agentClientActionMethods } =
    loadMethods(async () => ({ ...DOC }))
  const inst = {
    projectId: 1,
    // 合并比对稿也是这个窗格里的一个标签页，采纳裁决时它就在最前面。
    leftFiles: [{ ...DOC }, { id: MERGE_TAB_ID, name: '合并比对稿' }],
    rightFiles: [],
    libreLruKeys: ['left:' + DOC.id],
    libreSpares: spareHoldsDoc ? [{ key: 1, file: { ...DOC } }] : [],
    activeFileIdLeft,
    activeFileIdRight: null,
    _libreRefs: {
      ['left:' + DOC.id]: {
        file: { ...DOC },
        async reloadFromBackend() { calls.reloaded.push(DOC.id); return reloadResult },
      },
    },
    $t: (k) => k,
    $refs: {},
    openFile() { throw new Error('这份文件本来就开着，不该走到 openFile') },
  }
  Object.assign(inst, librePoolMethods, agentClientActionMethods)
  return { inst, calls }
}

test('非活动的过继备胎实例必须真的卸载（A1 回归）', async () => {
  const { inst } = page({ activeFileIdLeft: MERGE_TAB_ID, spareHoldsDoc: true })

  const ok = await inst.handleEditorReloadFile({ fileId: DOC.id }, { forceActive: true })

  assert.equal(ok, true)
  assert.deepEqual(inst.libreLruKeys, [], '常规池的记账要摘掉')
  assert.deepEqual(inst.libreSpares, [],
    '过继备胎槽也要摘掉——不摘它就一直挂着合并前的字节，律师切回去看到的还是旧正文')
})

test('活动的过继备胎不卸载，走就地重载', async () => {
  const { inst, calls } = page({ activeFileIdLeft: DOC.id, spareHoldsDoc: true })

  const ok = await inst.handleEditorReloadFile({ fileId: DOC.id }, { forceActive: true })

  assert.equal(ok, true)
  assert.deepEqual(calls.reloaded, [DOC.id], '正在显示的实例只能就地换文档')
  assert.equal(inst.libreSpares.length, 1, '活动实例逐不掉也不该逐')
  assert.deepEqual(inst.libreLruKeys, ['left:' + DOC.id])
})

test('非活动的常规池实例照旧摘出 LRU（不回归）', async () => {
  const { inst } = page({ activeFileIdLeft: MERGE_TAB_ID, spareHoldsDoc: false })

  await inst.handleEditorReloadFile({ fileId: DOC.id }, { forceActive: true })

  assert.deepEqual(inst.libreLruKeys, [])
})

test('卸载不误伤 id 以它结尾的别的文件', () => {
  const { inst } = page({ activeFileIdLeft: MERGE_TAB_ID, spareHoldsDoc: false })
  inst.libreLruKeys = ['left:1501', 'left:501', 'right:501']

  inst.unloadInactiveLibreInstances(501)

  assert.deepEqual(inst.libreLruKeys, ['left:1501'])
})
