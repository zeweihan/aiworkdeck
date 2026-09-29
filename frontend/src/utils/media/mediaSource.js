// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 媒体取源（规格 4.1 前端段）：媒体元素直连下载接口走 Range 流式播放，
// 鉴权只能放 query（媒体元素设不了请求头）。
// 红线：任何日志里打印这个 URL 之前必须先过 redactToken()。

/** 下载地址追加 ?token=<sessionId>；无 sessionId 原样返回（local-mode 桌面后端本就忽略）。 */
export function buildStreamUrl(downloadUrl, sessionId) {
  if (!downloadUrl || !sessionId) return downloadUrl
  const hashAt = downloadUrl.indexOf('#')
  const base = hashAt >= 0 ? downloadUrl.slice(0, hashAt) : downloadUrl
  const hash = hashAt >= 0 ? downloadUrl.slice(hashAt) : ''
  const sep = base.includes('?') ? '&' : '?'
  return base + sep + 'token=' + encodeURIComponent(sessionId) + hash
}

/** token=xxx → token=***（只认独立的 token 参数名）。 */
export function redactToken(url) {
  if (typeof url !== 'string' || !url) return url
  return url.replace(/([?&]token=)[^&#]*/gi, '$1***')
}

// MediaError.code：1 ABORTED / 2 NETWORK / 3 DECODE / 4 SRC_NOT_SUPPORTED。
// 2/4 可能是直链鉴权或 Range 不通，退回旧的 XHR blob 路径；只退一次，
// blob 也失败就是真的放不了，再退会死循环。
export function shouldFallbackToBlob(mediaErrorCode, alreadyFellBack) {
  if (alreadyFellBack) return false
  return mediaErrorCode === 2 || mediaErrorCode === 4
}
