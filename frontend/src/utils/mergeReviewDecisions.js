// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 合并比对稿「完成裁决」要提交的 decisions 清单（dev-board#630）。
//
// 纯函数、零依赖（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/version-merge/mergeReviewDecisions.test.mjs。
//
// 这份清单最终会经 `POST /version/merge/resolve-file` 落进待决记录，收尾时写成
// 提交尾注 `X-AWD-Merges: 合同.docx=manual:p3MA,p7TA,p12MA,p9X,p20F,t1.2.3MA`
// （格式见 spec §4.6），提交历史标签页再把它翻回律师能读的句子。所以这里产出的
// 每一条都是「哪一处、留了哪一边、怎么处置的」这笔账，**不是修订条数的账**：
// 同一段里的若干条修订同样处置只记一次（见下方去重）。
//
// 三块来源（spec §5.4 的审阅面板三块，顺序即清单顺序）：
//   块 1 同一段两边都改了 → 用你的 p12MA / 用律师乙的 p12TA / 自己改 p9X
//   块 2 逐条修订         → 接受 A、拒绝 R，键取修订所在段（表格取 t 键）
//   块 3 另一侧只改了格式 → p20F，只记录，没有侧别也没有处置

const SIDES = ['M', 'T']
const ACTIONS = ['A', 'R']

// 块 1 的三个按钮 → (side, action)。没选过的（choice 缺席/空/不认识）一律不进
// 清单——那一处还挡着「完成裁决」，把它当成已裁决就是在尾注里撒谎。
const CHOICE_MAP = {
  main: { side: 'M', action: 'A' },
  other: { side: 'T', action: 'A' },
  self: { side: '', action: 'X' },
}

/**
 * 一条修订落到哪个单元键上。
 * 表格里的段落不在正文段落序里（引擎 buildParaIndex 不枚举表格段落，
 * 见 spec §0「段落索引语义」），所以有 `t` 键就用 `t` 键；
 * 拿不到 `t` 键时退回段落序——键粗一点总好过少记一处裁决。
 * @param {{paraKey:number|string, inTable?:boolean, tableKey?:string}} r
 * @returns {string} 'p12' | 't1.2.3' | ''
 */
export function revisionDecisionKey(r) {
  if (!r) return ''
  if (r.tableKey) return String(r.tableKey)
  if (r.key) return String(r.key)
  const p = r.paraKey
  if (p === null || p === undefined || p === '' || Number(p) < 0) return ''
  return 'p' + p
}

/**
 * 一条 decision 的尾注写法（`p12MA` / `p9X` / `t1.2.3MA`）。
 * 只做拼接，不做编码——路径编码在后端，键里不会出现 `,` 与 `:`。
 */
export function decisionToken(d) {
  if (!d) return ''
  return `${d.key}${d.side || ''}${d.action || ''}`
}

function pushUnique(out, seen, decision) {
  const token = decisionToken(decision)
  if (!token || seen.has(token)) return
  seen.add(token)
  out.push(decision)
}

/**
 * 把审阅面板三块的状态合成一份 decisions 清单。
 *
 * @param {object} input
 * @param {Array<{key:string, choice:'main'|'other'|'self'|''}>} input.conflictChoices
 *        块 1：同一段两边都改了，每处律师选的那一边（没选的不传或 choice 为空）
 * @param {Array<{paraKey:number, inTable?:boolean, tableKey?:string, side:'M'|'T', action:'A'|'R'}>} input.revisionOutcomes
 *        块 2：逐条修订的处置，side 由宿主按修订作者映射（M = 主线侧，T = 另一边）
 * @param {Array<{paraKey?:number, key?:string, preview?:string}>} input.formatOnly
 *        块 3：另一侧只改了格式、没被自动带过来的段
 * @returns {Array<{key:string, side:string, action:string}>} 后端 `Decision` 同形
 */
export function collectDecisions({ conflictChoices = [], revisionOutcomes = [], formatOnly = [] } = {}) {
  const out = []
  const seen = new Set()

  for (const c of conflictChoices || []) {
    const key = c && c.key ? String(c.key) : ''
    const mapped = c ? CHOICE_MAP[c.choice] : null
    if (!key || !mapped) continue
    pushUnique(out, seen, { key, side: mapped.side, action: mapped.action })
  }

  for (const r of revisionOutcomes || []) {
    const key = revisionDecisionKey(r)
    if (!key) continue
    const side = r.side
    const action = r.action
    // 脏值（作者没映射上侧别、处置结果缺席）宁可整条丢掉也不写进尾注——
    // 尾注是给律师看的账，`p3ZA` 这种读不出意思的条目比少一条更糟。
    if (!SIDES.includes(side) || !ACTIONS.includes(action)) continue
    pushUnique(out, seen, { key, side, action })
  }

  for (const f of formatOnly || []) {
    const key = revisionDecisionKey(f)
    if (!key) continue
    pushUnique(out, seen, { key, side: '', action: 'F' })
  }

  return out
}
