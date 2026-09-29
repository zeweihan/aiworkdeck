// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// editorLoadFailure.js — LOWA 文档装载失败的分类与自愈判据（dev-board#539）。
//
// 为什么抽成纯函数：判据要能被 node --test 直接跑（tests/lowa-unit/），
// 而 LibreOfficeEditor.vue 带 @/ 别名与 uni 运行时，进不了单测。本模块
// 零依赖、零副作用。
//
// 背景：桌面端久置/失焦后回来，编辑器常常是空白页 + 红胶囊「文档加载失败」。
// 那一句话把三种完全不同的事故混成一个文案：
//   ① 后端 404（本地根目录文件被外部挪走）——重试一万次也没用，得告诉用户
//      文件不在磁盘上了；
//   ② 下载超时 / 网络错——检查网络后重试有意义；
//   ③ 引擎侧装载失败，含 relay 的 180s 墙钟超时（guest 被 Chromium 背景节流
//      冻住，Emscripten/Qt 事件循环停摆）——这一类可以靠重启引擎自愈。
// 三类分别落三个 statusKey，全部以 'Failed' 结尾，组件里既有的
// `statusKey.endsWith('Failed')` 判据（isError / finishDocLoad / saveDocument /
// reloadFromBackend 四处）因此原样成立，不必改判据。

/** 文件在后端已经不存在（404/410）。 */
export const STATUS_FILE_MISSING = 'fileMissingFailed'
/** 下载超时 / 网络错 / 其它非 404 的 HTTP 失败。 */
export const STATUS_DOWNLOAD_FAILED = 'downloadFailed'
/** 引擎侧装载失败（含 relay 超时）——原有文案，保持不变。 */
export const STATUS_LOAD_FAILED = 'loadFailed'

function textOf(err) {
  if (err == null) return ''
  if (typeof err === 'string') return err
  if (err.message) return String(err.message)
  return String(err)
}

/**
 * 把 loadDocument 抛出的异常分成三类 statusKey。
 * 匹配的是 fetchArrayBuffer 里 reject 的原始串（'HTTP 404' / '下载超时 …' /
 * '网络错误 …'）与 zetaOfficeRelay 的超时串——都是本仓自己写的固定文本，
 * 不随界面语言变化。
 *
 * @param {Error|string} err
 * @returns {'fileMissingFailed'|'downloadFailed'|'loadFailed'}
 */
export function classifyLoadFailure(err) {
  if (isRelayTimeout(err)) return STATUS_LOAD_FAILED
  const m = textOf(err)
  // 404/410 优先于下面的通用 HTTP 分支——「文件已不在磁盘上」是唯一一条
  // 「重试没有意义」的失败，不能被并进「下载失败，请检查网络」。
  if (/\bHTTP (404|410)\b/.test(m)) return STATUS_FILE_MISSING
  if (/\bHTTP \d{3}\b/.test(m)) return STATUS_DOWNLOAD_FAILED
  if (/下载超时|timed out|网络错误|network error/i.test(m)) return STATUS_DOWNLOAD_FAILED
  return STATUS_LOAD_FAILED
}

/**
 * 这条失败是不是 relay 的墙钟超时（host 端不再等，worker 侧未必真失败）。
 * 优先读取稳定错误码，并兼容旧版本的 relay timeout 文本。
 *
 * @param {Error|string} err
 */
export function isRelayTimeout(err) {
  return err?.code === 'EDITOR_RESULT_TIMEOUT' || /relay timeout/i.test(textOf(err))
}

/**
 * 装载失败后要不要重启引擎再来一次。
 *
 * 只对 relay 超时自愈：那说明 guest 的事件循环停摆（久置被背景节流冻住、
 * 渲染进程被系统回收），画布上留的是 boot 出来的空白原型——重启引擎是唯一
 * 出路。404 / 网络错重启引擎毫无用处，重启反而白烧几百 MB 内存与十几秒。
 *
 * **只自愈一次**：连续两次超时说明不是「冻住了」而是真装不动（超大文档 /
 * 引擎坏了），再循环下去就是无限重启。第二次照常落 loadFailed 让用户看见。
 *
 * @param {Error|string} err
 * @param {boolean} alreadyHealed 本轮装载是否已经自愈过一次
 */
export function shouldSelfHealLoadFailure(err, alreadyHealed) {
  return !alreadyHealed && isRelayTimeout(err)
}

// ---- 装载预算分级与「打不开」终态（dev-board#1018）----
//
// load_document 原先一律 180s 墙钟预算，超时后自愈重装一次再 180s：一份几十 KB 的
// AI 生成文档卡住时，用户要对着 95% 干等六七分钟才看到报错。按字节数分级——小文档
// 正常装载是亚秒到几秒（lowa-e2e 大文档基线：6.7MB/150 页 1.4–3.7s），30s 足以
// 区分「慢」和「不会回来了」。

/** 装载「打不开」终态：引擎拒收（文件损坏/格式不受支持）或重启重装后仍无响应。 */
export const STATUS_OPEN_FAILED = 'docOpenFailed'
/** 诊断码：引擎读了字节但两条装载路径都拒收（loadComponentFromURL 返回 null / 抛异常）。 */
export const DIAG_DOC_REJECTED = 'DOC_REJECTED'
/** 诊断码：重启引擎重装一次后 load_document 仍超过预算没有回音。 */
export const DIAG_LOAD_TIMEOUT = 'EDITOR_LOAD_TIMEOUT'

const MB = 1024 * 1024
export const LOAD_BUDGET_TIERS = Object.freeze([
  Object.freeze({ maxBytes: 1 * MB, ms: 30000 }),
  Object.freeze({ maxBytes: 10 * MB, ms: 90000 }),
])
export const LOAD_BUDGET_MAX_MS = 180000

/**
 * load_document 的等待预算（ms），按文档字节数分级：≤1MB 30s、≤10MB 90s、更大 180s。
 * 字节数未知/非法按最大档（宁可多等，不把正常的大文档误判成挂死）。
 * @param {number} byteLength
 */
export function loadBudgetMs(byteLength) {
  const n = Number(byteLength)
  if (!Number.isFinite(n) || n < 0) return LOAD_BUDGET_MAX_MS
  for (const t of LOAD_BUDGET_TIERS) if (n <= t.maxBytes) return t.ms
  return LOAD_BUDGET_MAX_MS
}

/**
 * 这次失败要不要落「文档无法打开」终态，以及诊断码。null = 不是这一类（走 classifyLoadFailure）。
 * - 引擎明确拒收（worker 回 code DOC_REJECTED）：重试/重启都没用，直接落终态；
 * - relay 超时且本轮已经自愈过一次：引擎无响应，落终态。
 * @param {Error|string} err
 * @param {boolean} alreadyHealed
 * @returns {null|{statusKey:string, code:string}}
 */
export function openFailureOf(err, alreadyHealed) {
  if (err && err.code === DIAG_DOC_REJECTED) return { statusKey: STATUS_OPEN_FAILED, code: DIAG_DOC_REJECTED }
  if (alreadyHealed && isRelayTimeout(err)) return { statusKey: STATUS_OPEN_FAILED, code: DIAG_LOAD_TIMEOUT }
  return null
}
