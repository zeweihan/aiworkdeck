// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 不重叠自动合并的编排（规格 docs/superpowers/specs/2026-09-14-docx-three-way-merge-design.md §5.2）。
//
// 三语境（采纳 / 取回 / 结束工作撞车）共用同一个入口：只要 /status 给出了冲突对象，
// 就按后端算好的 documentMerges 分流——
//   AUTO  的 docx  → 借一个不绑标签页的隐藏引擎实例，比较主线侧 + 逐段重放另一侧 →
//                    全部接受 → 导出 → POST resolve-file(mode=auto)；
//   AUTO  的 xlsx/pptx → 直接 POST resolve-structured，合并文件由后端 POI 拼，不碰引擎；
//   MANUAL / WHOLE → 不动，交给裁决总览。
// 全部路径都合好了才按 MERGED 收尾（打该语境的 resolve 端点），否则把人带到总览。
//
// 本文件是纯工厂（依赖全部注入，node --test 直接跑，形制照 useComponentDownloads.js）：
// api / 引擎 / 文案 / toast 全部由 project-overview.vue 在挂载时喂进来。
//
// 三条必须守住的不变式，各有一个用例（tests/version-merge/useDocumentMerge.test.mjs）：
//   ① 有任何一份不是 MERGED 就不收尾——律师还没被问过，收尾等于替他做了决定；
//   ② 幂等：/status 120 秒一轮、面板每次刷新也会再调一次，按 (另一侧 tip, path) 记账，
//      同一份文件不重放、同一次冲突不重复收尾；
//   ③ 引擎回 success:false（尤其 stage:'align'，段落对不上）时**一个字节都不写回去**：
//      那份导出件的段落是错位的，写回去比不合更糟。

// 两侧的人分不开时（同一个账号两边都是自己——律师用「稿」管对方回稿）喂给引擎的署名
// 要退到线上，否则引擎按作者分桶会把两边的修订全算进一桶（真机 A3：11 / 0）。
import { resolveMergeSideNames } from '../utils/mergeSideNames.js'

// 三语境里「另一侧」（MERGE_HEAD 那一侧）的 tip 字段名不同：
// adopt=draftTip、cloud=cloudTip、session-end=sessionTip。取错只是幂等键与取字节的 ref 错，
// 不会静默写错数据（后端按 MERGE_HEAD 反查，不信客户端），但拿不到字节就合不成。
function otherRefOf(conflict, ctx) {
  if (!conflict) return null
  if (ctx === 'cloud') return conflict.cloudTip || conflict.draftTip || null
  if (ctx === 'session-end') return conflict.sessionTip || conflict.draftTip || null
  return conflict.draftTip || conflict.cloudTip || conflict.sessionTip || null
}

function baseNameOf(path) {
  return String(path || '').split('/').pop() || String(path || '')
}

export function useDocumentMerge(deps) {
  const state = Object.assign(deps.state || {}, {
    // 当前这次冲突的每份文件一行（mergeRows.js 的输入）
    rows: [],
    // 有没有文件正在自动合并中（顶栏协作 chip 据此显示「正在合并同事的改动…」）
    running: false,
    startedAt: 0,
    ctx: null,
    sides: {},
    // 两侧的称呼与喂给引擎的那两个署名（resolveMergeSideNames 的出参）
    sideNames: {},
  })
  // 幂等记账：键是 `${另一侧 tip}|${path}`，值是这份文件这一轮已经处理过了。
  // 用 tip 而不是 projectId 做前缀，是因为中止一次合并后重新撞车时 MERGE_HEAD 会变，
  // 那时应该重新跑；同一次冲突里则永远只跑一次。
  const handled = new Set()
  const inflight = new Set()
  // 已经收过尾的冲突（同样按另一侧 tip 记），防止两次 /status 各收一次尾
  const finalized = new Set()

  const isDesktop = () => (typeof deps.isDesktop === 'function' ? !!deps.isDesktop() : !!deps.isDesktop)
  const t = deps.t || ((k) => k)

  function rowFor(path) {
    return state.rows.find((r) => r.path === path) || null
  }

  // 隐藏实例借一次还一次。release 必须在 finally 里，失败路径也要还——
  // 不还的话每撞一次车就多留一个常驻 LOWA 实例（数百 MB），律师那边表现为越用越卡。
  async function withHiddenEngine(fn) {
    let handle = null
    try {
      handle = await deps.getExecutorForHiddenInstance()
    } catch (e) {
      console.warn('[DocumentMerge] 取隐藏引擎实例失败', e)
      handle = null
    }
    if (!handle) return { engine: false }
    try {
      const run = typeof handle.run === 'function'
        ? (action, payload) => handle.run(action, payload)
        : (action, payload) => handle.executeCommand(action, payload)
      return { engine: true, result: await fn(run, handle) }
    } finally {
      try { deps.releaseHiddenInstance(handle) } catch (e) { console.warn('[DocumentMerge] 归还隐藏实例失败', e) }
    }
  }

  async function autoMergeDocx(row, refs) {
    const name = baseNameOf(row.path)
    const out = await withHiddenEngine(async (run) => {
      const inputs = await deps.fetchMergeInputs(deps.projectId, row.path, refs)
      const draft = await deps.buildMergeDraft(run, inputs, {
        mainAuthor: state.sideNames.mainKey || '',
        otherAuthor: state.sideNames.otherKey || '',
        name,
      })
      if (!draft || draft.success !== true) {
        return { failed: true, failReason: (draft && draft.stage) || 'build' }
      }
      // 另一侧只改了格式的段落带不过来（文字重放带不动格式）——这不是失败，
      // 是"不能静默合"：降成 MANUAL 交给合并比对稿，让律师看见那几段自己套格式。
      if ((draft.formatOnly || []).length > 0) {
        return { downgrade: true, formatOnlyCount: draft.formatOnly.length }
      }
      await run('resolve_all_revisions', { action: 'accept' })
      const exported = await run('export_document', { name })
      const bytes = exported && (exported.bytes || exported.data)
      if (!exported || exported.success === false || !bytes || !bytes.length) {
        return { failed: true, failReason: 'export' }
      }
      await deps.api.postMergeResolveFile(deps.projectId, {
        path: row.path, mode: 'auto', decisions: [], bytes, name, ctx: state.ctx,
      })
      // 刻意不把 draft.mainCount/otherCount 带出去：那是「这个作者名下有几条修订」，
      // 不是「这一侧改了几处」——两侧同名时它就是 11/0。行上的「处数」一律用后端
      // 三方比对给的 mainChanges/otherChanges（按单元数，与署名无关）。
      return { merged: true }
    })
    if (out.engine === false) return { failed: true, failReason: 'engine' }
    return out.result
  }

  async function autoMergeStructured(row) {
    await deps.api.postMergeResolveStructured(deps.projectId, { path: row.path, decisions: [] })
    return { merged: true }
  }

  function applyOutcome(row, outcome) {
    if (!outcome) return
    if (outcome.merged) {
      row.state = 'MERGED'
      row.failed = false
      return
    }
    if (outcome.downgrade) {
      row.decision = 'MANUAL'
      row.formatOnlyCount = outcome.formatOnlyCount
      return
    }
    if (outcome.failed) {
      row.failed = true
      row.failReason = outcome.failReason
    }
  }

  async function finalize(conflict, ctx, otherRef) {
    const paths = (conflict.conflictingPaths || []).length
      ? conflict.conflictingPaths
      : state.rows.map((r) => r.path)
    if (!paths.length) return
    const allMerged = paths.every((p) => {
      const row = rowFor(p)
      return row && String(row.state || '').toUpperCase() === 'MERGED'
    })
    if (!allMerged) {
      if (typeof deps.openOverview === 'function') deps.openOverview()
      return
    }
    const key = String(otherRef || '') + '|__finalize__'
    if (finalized.has(key)) return
    finalized.add(key)
    const resolutions = {}
    for (const p of paths) resolutions[p] = 'MERGED'
    try {
      let res
      if (ctx === 'cloud') res = await deps.api.resolveCloudMerge(deps.projectId, resolutions)
      else if (ctx === 'session-end') res = await deps.api.resolveSessionEnd(deps.projectId, conflict.sessionId, resolutions)
      else res = await deps.api.resolveAdopt(deps.projectId, conflict.draftId, resolutions)
      const data = (res && res.data) || {}
      if (typeof deps.toast === 'function') {
        deps.toast(t('version.mergeAutoDone', { count: paths.length }))
      }
      if (typeof deps.reloadFiles === 'function') deps.reloadFiles(data.affectedFileIds || [])
    } catch (e) {
      // 收尾失败不是数据事故（字节已经落在工作区、待决记录也在），但律师要知道
      // 这次没走完——把人带到总览，那里还能手动「确认选择」。
      console.warn('[DocumentMerge] 自动收尾失败', e)
      finalized.delete(key)
      if (typeof deps.toast === 'function') deps.toast((e && e.message) || t('version.mergeAutoFinalizeFailed'))
      if (typeof deps.openOverview === 'function') deps.openOverview()
    }
  }

  // 一轮冲突会被连着触发好几次（协作抽屉撞冲突时 conflict 与 changed 两个事件各调一次
  // checkAdoptConflict，侧栏面板重新挂载又是一次），所以这条**必须串起来跑**：并发进来的
  // 第二次会把 state.rows 换成一份新数组，正在跑的第一次于是把「这份合好了」写在了被换掉
  // 的旧对象上，finalize 读新数组只看到 PENDING——两份文件明明都已经合好落盘（后端
  // /status 的 state 也是 MERGED），收尾却永远不发生，律师停在一个说着「已合并」、
  // 却要他再点一次「就按我选的来」才关得掉的裁决窗前。
  // app-e2e J14 实测复现：xlsx + pptx 两份不重叠改动，后端日志里 PptxMerger 跑了两遍
  // （两次并发各合了一次），随后仓库一直停在 MERGING。
  let queue = Promise.resolve()
  function onConflictStatus(conflict, ctx) {
    queue = queue.then(() => runConflictStatus(conflict, ctx), () => runConflictStatus(conflict, ctx))
    return queue
  }

  async function runConflictStatus(conflict, ctx) {
    if (!conflict) {
      state.rows = []
      state.running = false
      state.ctx = null
      state.sides = {}
      state.sideNames = {}
      return
    }
    const otherRef = otherRefOf(conflict, ctx)
    state.ctx = ctx
    state.sides = conflict.sides || {}
    state.sideNames = resolveMergeSideNames(t, state.sides,
      { mode: ctx, draftName: conflict.draftName })
    // 保留上一轮挂在行上的前端字段（failed / formatOnlyCount）：
    // /status 每轮都给一份全新的 documentMerges，直接覆盖会把"自动合并失败"抹成
    // "正在合并"，律师看到的是一个永远转不完的圈。
    const incoming = conflict.documentMerges || []
    state.rows = incoming.map((r) => {
      const prev = rowFor(r.path)
      // state 同理：本地已经知道「这份合好了」时，绝不让一份更老的 /status 快照把它
      // 抹回 PENDING——抹回去了界面就从「已合并」倒退成「正在合并」，转一个永远不来的圈。
      return prev
        ? {
          ...prev,
          ...r,
          decision: prev.decision === 'MANUAL' ? 'MANUAL' : r.decision,
          state: String(prev.state || '').toUpperCase() === 'MERGED' ? 'MERGED' : r.state,
        }
        : { ...r }
    })

    const refs = { mergeBase: conflict.mergeBase, mainRef: conflict.mainlineTip, otherRef }
    const pending = state.rows.filter((row) => {
      if (String(row.state || '').toUpperCase() === 'MERGED') return false
      if (String(row.decision || '').toUpperCase() !== 'AUTO') return false
      if (row.failed) return false
      const kind = String(row.kind || '').toUpperCase()
      if (kind === 'DOCX' && !isDesktop()) return false
      const key = String(otherRef || '') + '|' + row.path
      if (handled.has(key) || inflight.has(key)) return false
      return true
    })

    if (pending.length) {
      state.running = true
      state.startedAt = Date.now()
      try {
        for (const row of pending) {
          const key = String(otherRef || '') + '|' + row.path
          inflight.add(key)
          try {
            const kind = String(row.kind || '').toUpperCase()
            const outcome = kind === 'DOCX' ? await autoMergeDocx(row, refs) : await autoMergeStructured(row)
            applyOutcome(row, outcome)
            handled.add(key)
          } catch (e) {
            console.warn('[DocumentMerge] 自动合并失败', row.path, e)
            applyOutcome(row, { failed: true, failReason: 'build' })
            handled.add(key)
          } finally {
            inflight.delete(key)
          }
        }
      } finally {
        state.running = false
      }
    }

    await finalize(conflict, ctx, otherRef)
  }

  // 「重试自动合并」：清掉这份文件的失败记账与幂等锁，下一轮 /status 会重新跑它。
  // 不在这里直接重跑——冲突对象（三个 ref、sides、语境）是 /status 给的，
  // 重跑要用的是最新那一份，不是上一轮缓存下来的。
  function retry(path) {
    const row = rowFor(path)
    if (row) { row.failed = false; row.failReason = null }
    for (const key of Array.from(handled)) {
      if (key.endsWith('|' + path)) handled.delete(key)
    }
  }

  return { onConflictStatus, retry, state }
}
