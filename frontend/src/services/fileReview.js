// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 计划审阅（dev-board#1022）的审阅记录与批注 REST：/api/projects/{pid}/files/{fid}/review。
//
// 错误约定（后端 FileReviewController）：鉴权与归属错误照全站口径回 HTTP 200 + code≠0
// （登出 4010、越权 1），由 api.request 统一 reject；真 HTTP 状态码只有 409（没有 open 记录）、
// 400（批注为空）、204（GET 无记录 / DELETE 成功）。api.request 对非 200 一律 reject 并在
// err.status 上带状态码，所以 204 在这里接住：GET 回 null，DELETE 回 true。
// 成功响应是裸对象（无 code 信封），request 原样返回。
import api from '@/services/api.js'

const base = (pid, fid) => `/api/projects/${pid}/files/${fid}/review`
const json = { 'Content-Type': 'application/json' }
const isNoContent = (e) => !!e && e.status === 204

/** 进入审阅态（幂等：已有 open 记录原样返回、不覆盖基线）。返回 { review, comments }。 */
export function openReview(pid, fid, body) {
  return api.request({ url: base(pid, fid), method: 'POST', data: body || {}, header: json })
}

/** 当前 open 记录；没有时回 null。返回 { review, comments } | null。 */
export async function getReview(pid, fid) {
  try {
    const r = await api.request({ url: base(pid, fid), method: 'GET' })
    return r || null
  } catch (e) {
    if (isNoContent(e)) return null
    throw e
  }
}

/** 加一条批注：body = { fromLine, toLine, quotedText, body }。返回批注对象。 */
export function addComment(pid, fid, body) {
  return api.request({ url: base(pid, fid) + '/comments', method: 'POST', data: body, header: json })
}

export async function deleteComment(pid, fid, cid) {
  try {
    await api.request({ url: base(pid, fid) + '/comments/' + cid, method: 'DELETE' })
    return true
  } catch (e) {
    if (isNoContent(e)) return true
    throw e
  }
}

/** 提交：记录置 submitted，返回 { review, comments } 快照。 */
export function submitReview(pid, fid) {
  return api.request({ url: base(pid, fid) + '/submit', method: 'POST', data: {}, header: json })
}

/** 放弃：服务端把文件写回基线、记录置 discarded。返回 { review }。 */
export function discardReview(pid, fid) {
  return api.request({ url: base(pid, fid) + '/discard', method: 'POST', data: {}, header: json })
}
