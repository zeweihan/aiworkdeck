// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「点交稿要不要先摆清单」的**判据来源**（dev-board 0.44.1 清单 B3，src/utils/submitGuide.js）。
 *
 * 真机复现：放进案件库 → 改一处（后端隐式开了一段工作）→ 协作抽屉 → 交稿，
 * 得到的是一句「已交稿」，三步清单一次都没出现，状态也没变。
 *
 * 病灶不在 submitGuideSteps 的映射（那份映射是对的，submitGuide.test.mjs 已钉住），
 * 而在喂给它的 working 是哪来的：#848 直接用了 project-overview 递下来的
 * versionWorkStatus.working，而那份快照只在进页面 / 切回窗口 / 版本面板动作 /
 * 协作动作之后才重读 /version/status——**律师刚才的那次编辑不会刷新它**
 * （120 秒的协作轮询只刷 cloudStatus）。于是判据恒是陈旧的 false，
 * 恰好 remoteAhead 也为假时（同事没交新稿，也就是最常见的情形）清单整个不出。
 *
 * #848 的 app-e2e 那一步之所以是绿的：它跑在「案件库已经领先」之后，
 * 靠 remoteAhead 这一条把拦截拦住了，从来没有覆盖「只欠结束工作」这一路。
 *
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { submitNeedsGuide } from '../../src/utils/submitGuide.js'

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const read = (p) => readFileSync(resolve(SRC, p), 'utf8')

/** 记一次 /version/status 的读取，回指定的 working。 */
const reader = (working) => {
  const calls = []
  const readStatus = async (projectId) => {
    calls.push(projectId)
    return { code: 0, data: { enabled: true, working } }
  }
  return { calls, readStatus }
}

// ---------------- 判据必须是现读的 ----------------

test('真机复现：页面快照说 working=false、案件库没领先，但现读出来在工作中 → 必须先摆清单', async () => {
  const { calls, readStatus } = reader(true)
  const needs = await submitNeedsGuide({
    projectId: 235,
    working: false, // 页面那份陈旧快照：进页面时读的，律师随后的编辑没刷新它
    cloud: { linked: true, remoteAhead: false, pendingUpload: true },
    readStatus,
  })
  assert.equal(needs, true, '工作中点交稿必须被拦下来摆清单，不能一路走到空交稿')
  assert.deepEqual(calls, [235], '判据必须现读一次 /version/status')
})

test('现读出来确实没有在工作、案件库也没领先：不拦，直接走交稿', async () => {
  const { readStatus } = reader(false)
  assert.equal(await submitNeedsGuide({ projectId: 1, working: false, cloud: { remoteAhead: false }, readStatus }), false)
})

test('页面快照说在工作、现读说已经收尾了（律师刚在版本面板结束了本次工作）：不拦', async () => {
  const { readStatus } = reader(false)
  assert.equal(await submitNeedsGuide({ projectId: 1, working: true, cloud: {}, readStatus }), false)
})

test('案件库领先这一条仍走 cloud 快照，现读没在工作也要拦', async () => {
  const { readStatus } = reader(false)
  assert.equal(
    await submitNeedsGuide({ projectId: 1, working: false, cloud: { remoteAhead: true, remoteAheadCount: 6 }, readStatus }),
    true,
  )
})

test('连不上案件库（cloud.offline）照样拦下来——摆的是那张「改动都在本机」的说明', async () => {
  const { readStatus } = reader(false)
  assert.equal(await submitNeedsGuide({ projectId: 1, working: false, cloud: { offline: true }, readStatus }), true)
})

// ---------------- 读不到状态时的退路 ----------------

test('现读失败：退回页面快照，不许因为一次网络抖动把交稿永久堵死', async () => {
  const boom = async () => { throw new Error('offline') }
  assert.equal(await submitNeedsGuide({ projectId: 1, working: false, cloud: {}, readStatus: boom }), false)
  assert.equal(await submitNeedsGuide({ projectId: 1, working: true, cloud: {}, readStatus: boom }), true)
})

test('回包里没有 working 字段（老服务端 / 版本记录没开）：同样退回页面快照', async () => {
  const empty = async () => ({ code: 0, data: { enabled: false } })
  assert.equal(await submitNeedsGuide({ projectId: 1, working: true, cloud: {}, readStatus: empty }), true)
  assert.equal(await submitNeedsGuide({ projectId: 1, working: false, cloud: {}, readStatus: empty }), false)
})

test('cloud 里万一也有个 working 键，不许盖掉刚现读出来的那一份', async () => {
  const { readStatus } = reader(true)
  const needs = await submitNeedsGuide({
    projectId: 1, working: false, cloud: { working: false, remoteAhead: false }, readStatus,
  })
  assert.equal(needs, true)
})

// ---------------- 三处入口共用同一份判据（源码级） ----------------
// 三份各自判断必然走散，而判错的后果是律师看到一句「已交稿」，实际什么都没交。

for (const file of ['components/collab/CollabDialog.vue', 'components/version/CommitHistoryTab.vue']) {
  test(`${file} 的交稿判据走 submitNeedsGuide，不再拿页面快照直接判`, () => {
    const src = read(file)
    assert.ok(src.includes('submitNeedsGuide'), '必须调共用的那份现读判据')
    assert.ok(
      !src.includes('submitGuideSteps'),
      '不许在交稿入口自己算一遍步骤：那条路只拿得到页面那份不会因为编辑而刷新的 working 快照',
    )
  })
}

// ---------------- 后端那一档的兜底（NOTHING_TO_SUBMIT） ----------------
// 现读也会漏：读失败退回快照、或读完到 push 之间才开的工作段。那时后端回
// NOTHING_TO_SUBMIT + 一句「先结束本次工作」（CloudSyncService.UploadStatus），
// 界面不许再弹「已交稿」。

for (const file of ['components/collab/CollabDialog.vue', 'components/version/CommitHistoryTab.vue']) {
  test(`${file} 收到 NOTHING_TO_SUBMIT 时摆清单，不走「已交稿」那一支`, () => {
    const src = read(file)
    const i = src.indexOf("NOTHING_TO_SUBMIT")
    assert.ok(i > 0, '必须显式认这一档，不能只靠 else 落到通用失败文案')
    const branch = src.slice(i, i + 500)
    assert.ok(branch.includes("$emit('submit-guide')"), '这一档要把三步清单摆出来')
    assert.ok(branch.includes('d.message'), '优先原样说后端那句话（它才知道欠的是哪一步）')
    assert.ok(
      !branch.slice(0, branch.indexOf("$emit('submit-guide')")).includes("version.submitted"),
      '这一档绝不许弹「已交稿」',
    )
  })
}

test('引导弹窗自己那一步把后端那句话原样说出来，不自己拼文案', () => {
  const src = read('components/collab/SubmitDraftGuide.vue')
  const submit = src.slice(src.indexOf('async doSubmit()'))
  assert.ok(submit.includes('d.message'), 'doSubmit 的兜底分支要原样显示后端 message')
})

test('后端 UploadStatus 里确实有 NOTHING_TO_SUBMIT 这一档（前后端对得上）', () => {
  const java = readFileSync(
    resolve(SRC, '../../backend/src/main/java/com/checkba/version/CloudSyncService.java'),
    'utf8',
  )
  const m = java.match(/enum UploadStatus \{([^}]*)\}/)
  assert.ok(m, '找不到 UploadStatus 枚举')
  assert.ok(
    m[1].split(',').map((s) => s.trim()).includes('NOTHING_TO_SUBMIT'),
    '前端认的这个状态名必须与后端枚举逐字一致',
  )
})

test('引导弹窗开窗时先把三个按钮压住，等自己那次重读落地再放开', () => {
  const src = read('components/collab/SubmitDraftGuide.vue')
  // 开窗那一刻 localWorking 仍是页面那份陈旧快照，清单会先按「可以交稿了」渲染一下，
  // 第 ③ 步是可点的主按钮——那一下点下去就是一次什么都没交的空交稿。
  const watchBody = src.slice(src.indexOf('visible(v) {'), src.indexOf('methods: {'))
  assert.ok(watchBody.includes('this.busy = true'), '开窗后到重读落地之间必须 busy（三个按钮都带 awd-btn-disabled）')
  assert.ok(
    /refresh\(\{\s*online:\s*true\s*\}\)[\s\S]{0,120}busy = false/.test(watchBody),
    '重读落地后才放开按钮',
  )
})
