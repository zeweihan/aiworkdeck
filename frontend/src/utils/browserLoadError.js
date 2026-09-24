// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 内嵌浏览器（BrowserView）加载失败态的纯函数：dev-board#889。
//
// 病灶：桌面端 makeBrowserView 一直监听着 did-fail-load，但只 console.warn 了一声，
// 从没把失败传给渲染层——AX 能看到 chrome-error://chromewebdata/（Electron 对主帧
// 失败的标准兜底页），面板这一侧却什么都不知道，于是呈现为「无加载中、无错误、
// 内容区一片空白」。修法是把这条事件转发给 Vue 层；转发之前要不要显示、显示成
// 哪句话，抽成这两个纯函数单独测，不依赖起一个真的 Electron。
//
// net error code 均为 Chromium net::ERR_* 的负数编号。

// errorCode → panels.js 里的翻译键。没收录的一律落 bpErrorGeneric，
// 调用方把 errorDescription 原文当小字详情兜底展示。
const ERROR_CODE_KEYS = Object.freeze({
  '-2': 'bpErrorFailed',
  '-6': 'bpErrorFileNotFound',
  '-7': 'bpErrorTimedOut',
  '-21': 'bpErrorNetworkChanged',
  '-100': 'bpErrorConnectionClosed',
  '-101': 'bpErrorConnectionReset',
  '-102': 'bpErrorConnectionRefused',
  '-104': 'bpErrorConnectionFailed',
  '-105': 'bpErrorNameNotResolved',
  '-106': 'bpErrorInternetDisconnected',
  '-107': 'bpErrorCertInvalid',
  '-109': 'bpErrorAddressUnreachable',
  '-118': 'bpErrorTimedOut',
  '-137': 'bpErrorNameNotResolved',
  '-200': 'bpErrorCertInvalid',
  '-201': 'bpErrorCertInvalid',
  '-202': 'bpErrorCertInvalid',
  '-203': 'bpErrorCertInvalid',
  '-207': 'bpErrorCertInvalid',
  '-501': 'bpErrorInsecureResponse'
})

// ERR_ABORTED：站点自己的重定向、用户点了别的链接、面板恰好在这时候被摘下重挂，
// 都会以这个 code 触发 did-fail-load——是最常见的误报，不是真的访问不了，必须过滤，
// 否则每一次正常的重定向都会让用户看到一闪而过的「无法访问」。
const IGNORED_ERROR_CODES = new Set([-3])

/**
 * 这一次 did-fail-load 是否要展示成用户可见的错误态。
 * @param {number|string} errorCode
 * @returns {boolean}
 */
function shouldShowLoadError(errorCode) {
  const code = Number(errorCode)
  if (!Number.isFinite(code) || code === 0) return false
  if (IGNORED_ERROR_CODES.has(code)) return false
  return true
}

/**
 * errorCode → panels.js 翻译键。
 * @param {number|string} errorCode
 * @returns {string}
 */
function loadErrorMessageKey(errorCode) {
  return ERROR_CODE_KEYS[String(errorCode)] || 'bpErrorGeneric'
}

export { shouldShowLoadError, loadErrorMessageKey, ERROR_CODE_KEYS, IGNORED_ERROR_CODES }
