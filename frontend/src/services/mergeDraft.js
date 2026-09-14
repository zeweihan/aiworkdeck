// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 合并比对稿的输入装配与引擎调用（dev-board#630，spec §5.1–5.2）。
//
// 两个调用方：自动合并流程（`composables/useDocumentMerge.js`，隐藏实例，静默跑完
// 就上传）与合并比对稿标签页（`components/version/MergeReviewTab.vue`，可见实例，
// 律师逐处裁决）。两边要的是同一份输入与同一条引擎链，所以放在这里而不是各写一遍。

import { fetchVersionFileBytes, getMergeAnalysis } from '@/services/api.js'

/**
 * 取三方合并要的三份字节与后端算好的分析结果。
 * 三次取字节互不依赖，并行拿——串行只会把打开合并比对稿的等待时间乘三。
 *
 * @param {number|string} projectId
 * @param {string} path 仓库内路径（冲突清单里的那一条）
 * @param {{mergeBase:string, mainRef:string, otherRef:string}} refs
 * @returns {Promise<{baseBytes:Uint8Array, mainBytes:Uint8Array, otherBytes:Uint8Array, analysis:object}>}
 */
export async function fetchMergeInputs(projectId, path, { mergeBase, mainRef, otherRef }) {
  const [baseBytes, mainBytes, otherBytes, analysisResp] = await Promise.all([
    fetchVersionFileBytes(projectId, mergeBase, path),
    fetchVersionFileBytes(projectId, mainRef, path),
    fetchVersionFileBytes(projectId, otherRef, path),
    getMergeAnalysis(projectId, path),
  ])
  const analysis = analysisResp && analysisResp.data !== undefined ? analysisResp.data : analysisResp
  return { baseBytes, mainBytes, otherBytes, analysis }
}

/**
 * 在引擎里造一份合并比对稿：主线侧原生比较 + 另一侧按计划逐段重放修订。
 * 整条链必须在**一条** worker 命令内跑完——修订作者只在同一条命令内切得动
 * （spike A2，`execCommand` 每条命令开头都会把作者重置回本机用户）。
 *
 * @param {(action:string, payload:object)=>Promise<object>} run 宿主的命令通道
 * @param {{baseBytes:Uint8Array, mainBytes:Uint8Array, otherBytes:Uint8Array, analysis:object}} inputs
 * @param {{mainAuthor:string, otherAuthor:string, name:string}} opts
 * @returns {Promise<object>} build_merge_draft 的原样结果（success/revisions/conflicts/formatOnly/…）
 */
export function buildMergeDraft(run, inputs, { mainAuthor, otherAuthor, name }) {
  const analysis = (inputs && inputs.analysis) || {}
  return run('build_merge_draft', {
    baseBytes: inputs.baseBytes,
    mainBytes: inputs.mainBytes,
    otherBytes: inputs.otherBytes,
    mainAuthor,
    otherAuthor,
    plan: analysis.plan || { mainChunks: [], otherChunks: [] },
    baseUnits: analysis.baseUnits || [],
    name,
  })
}
