// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 合并比对稿的两个准备动作：取三份字节 + 分析（fetchMergeInputs），
// 把它们喂给引擎的一条命令（buildMergeDraft）。
// 自动合并（composables/useDocumentMerge.js）与合并比对稿标签页（MergeReviewTab.vue）
// 共用这一份，两处的输入必须完全一致——否则「自动合出来的」和「律师在比对稿里看到的」
// 会是两份不同的文档，而这件事没有任何测试能自动发现。
//
// 规格：docs/superpowers/specs/2026-09-14-docx-three-way-merge-design.md §5.1 / §5.2。

import { fetchVersionFileBytes, getMergeAnalysis } from '@/services/api.js'

/**
 * 取一份冲突文件的三方输入。
 * @param {number|string} projectId
 * @param {string} path 仓库相对路径
 * @param {{mergeBase: string, mainRef: string, otherRef: string}} refs
 *        mergeBase = 两边分头改之前的那一版；mainRef = 主线侧尖端；otherRef = 另一侧尖端。
 *        三个 ref 的物理侧随语境不同（方向表见 .claude/agents/version-control.md
 *        「三语境冲突判定链」），本函数不做任何方向推导，调用方给什么用什么。
 * @returns {Promise<{baseBytes: Uint8Array, mainBytes: Uint8Array, otherBytes: Uint8Array, analysis: object}>}
 */
export async function fetchMergeInputs(projectId, path, { mergeBase, mainRef, otherRef }) {
  // 四件事互不依赖，串行只是白等（单份 docx 几 MB，本机后端读 blob 也要几十毫秒）。
  const [baseBytes, mainBytes, otherBytes, analysisRes] = await Promise.all([
    fetchVersionFileBytes(projectId, mergeBase, path),
    fetchVersionFileBytes(projectId, mainRef, path),
    fetchVersionFileBytes(projectId, otherRef, path),
    getMergeAnalysis(projectId, path),
  ])
  return {
    baseBytes,
    mainBytes,
    otherBytes,
    analysis: (analysisRes && analysisRes.data) || analysisRes || null,
  }
}

/**
 * 让引擎生成合并比对稿：主线侧改动做成原生比较修订、另一侧不冲突的块逐段重放。
 * 整条链必须在**一条 worker 命令**里做完——修订作者只在同一条命令内设得住
 * （execCommand 每条命令开头都会把作者重置成本机用户，spike A2），跨命令切作者
 * 会把两边的改动全签成同一个人，律师再也分不出哪处是谁改的。
 *
 * @param {(action: string, payload: object) => Promise<object>} run 引擎命令执行器
 * @param {{baseBytes, mainBytes, otherBytes, analysis}} inputs fetchMergeInputs 的出参
 * @param {{mainAuthor: string, otherAuthor: string, name: string}} opts 两侧署名与文件名
 * @returns {Promise<object>} build_merge_draft 的原样出参
 *          成功 {success:true, revisions, mainCount, otherCount, conflicts, formatOnly, elapsedMs}
 *          失败 {success:false, stage, message}
 */
export async function buildMergeDraft(run, inputs, { mainAuthor, otherAuthor, name }) {
  const analysis = (inputs && inputs.analysis) || {}
  return run('build_merge_draft', {
    baseBytes: inputs.baseBytes,
    mainBytes: inputs.mainBytes,
    otherBytes: inputs.otherBytes,
    mainAuthor: mainAuthor || '',
    otherAuthor: otherAuthor || '',
    plan: analysis.plan || null,
    baseUnits: analysis.baseUnits || [],
    name,
  })
}
