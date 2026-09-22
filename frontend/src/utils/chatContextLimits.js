// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 上下文层的上限与降级判据（dev-board#801 K21 ⑧⑨、#793 K14）。
//
// 病灶（审查 E-6 / E-7 / E-14）：这些上限原来全是**事后**生效的——
//   · 用户拖 15 份材料进去、界面上 15 个标签都在，后端静默只读前 10 份；
//   · 贴第 5 张图，那一张悄悄变成 OCR 文本；贴一张 12MB 的扫描件，
//     模型拿到的「正文」是一句 `[System: 文件超过大小限制]`；
//   · 「模型看不了图」的提示嵌在粘贴缩略图块里，从文件树拖进来的项目图片零提示。
// 长期原则 2 要求：任何上限在**触发前**拦截并明示，任何降级在界面上有对应表达。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/project-home/chat-context-layer.test.mjs。

/**
 * 拉不到 `GET /api/ai/config` 时的兜底上限。
 *
 * **必须与后端 application.yml 的 ai.context 默认值一致**：这里写小了会误拦，
 * 写成 Infinity 等于这道闸不存在。真值以服务端下发的为准（单一事实来源），
 * 这份只是网络抖动时的最后一道兜底。
 */
export const DEFAULT_CONTEXT_LIMITS = Object.freeze({
  maxFilesPerContext: 10,
  maxCharsPerFile: 50000,
  maxCharsActiveDocument: 200000,
  maxImagesPerTurn: 4,
  maxImageBytes: 10 * 1024 * 1024,
  // 后端口径：视觉直送的图片**也占** maxFilesPerContext 的配额。
  // 分开算会与后端差出 maxImagesPerTurn 份。
  visionCountsTowardFileQuota: true,
})

/** 只接受正整数；0 / 负数 / NaN / 字符串一律退回兜底——拿 0 去拦截会让用户一份都加不进来。 */
function positive(value, fallback) {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) && n > 0 ? n : fallback
}

/** 把 /api/ai/config 的 contextLimits 归一成可直接用来拦截的数字。 */
export function normalizeContextLimits(raw) {
  const src = raw && typeof raw === 'object' ? raw : {}
  return {
    maxFilesPerContext: positive(src.maxFilesPerContext, DEFAULT_CONTEXT_LIMITS.maxFilesPerContext),
    maxCharsPerFile: positive(src.maxCharsPerFile, DEFAULT_CONTEXT_LIMITS.maxCharsPerFile),
    maxCharsActiveDocument: positive(src.maxCharsActiveDocument, DEFAULT_CONTEXT_LIMITS.maxCharsActiveDocument),
    maxImagesPerTurn: positive(src.maxImagesPerTurn, DEFAULT_CONTEXT_LIMITS.maxImagesPerTurn),
    maxImageBytes: positive(src.maxImageBytes, DEFAULT_CONTEXT_LIMITS.maxImageBytes),
    visionCountsTowardFileQuota: src.visionCountsTowardFileQuota !== false,
  }
}

/** 能直送模型的图片扩展名，与后端 ai.context.vision.extensions 一致（**刻意不含 pdf**）。 */
const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp']

/**
 * 这个附件是不是图片。**不能只看 fileType**：项目树里的 fileType 是后端原样透传的，
 * 桌面端那张扩展名映射表没有 bmp，.bmp 在项目树里是 'other'；两条判据取并集
 * （与 office-addin/taskpane/lib/chatSession.js 的 isImageAttachment 同口径）。
 */
export function isImageAttachment(item) {
  if (!item) return false
  if (String(item.fileType || '').toLowerCase() === 'image') return true
  const name = String(item.name || '')
  const dot = name.lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) return false
  return IMAGE_EXTENSIONS.indexOf(name.slice(dot + 1).toLowerCase()) !== -1
}

/**
 * 「当前模型看不了图」的常驻提示文案键（空串 = 不提示）。
 *
 * <p>照插件 visionNotice 的判据：**只要附件里有图就恒提示**。
 * 一次性 toast 覆盖不了「先选模型、过一会儿才加图片」，加图片时提示一次又覆盖不了
 * 「先加图片、后换模型」——桌面端原来做了两个各漏一半的版本。
 *
 * <p>`modelVision` 是三态：true 支持 / false 不支持 / null 未知。
 * **未知一律不提示**——拉不到模型目录时会对所有模型误报「不支持读图」。
 */
export function visionNoticeKey({ modelVision, pastedImages, contextFiles }) {
  if (modelVision !== false) return ''
  const hasPasted = Array.isArray(pastedImages) && pastedImages.length > 0
  const hasDragged = Array.isArray(contextFiles) && contextFiles.some(isImageAttachment)
  return hasPasted || hasDragged ? 'chat.imageOcrFallbackNote' : ''
}

/**
 * 本轮已经占掉多少份配额。
 *
 * <p>文件与图片算在一起：后端 `maxFilesPerContext` 是跨类型共享的总闸，
 * 直送的图片也递增它。分开算的话前端按 10 拦、后端按 10 砍，
 * 中间差出 maxImagesPerTurn 份——用户看到的就是「明明没到 10」却被丢了。
 */
export function contextQuotaState({ contextFiles, pastedImages, limits }) {
  const lim = limits || DEFAULT_CONTEXT_LIMITS
  const files = Array.isArray(contextFiles) ? contextFiles.length : 0
  const images = Array.isArray(pastedImages) ? pastedImages.length : 0
  const used = lim.visionCountsTowardFileQuota ? files + images : files
  return { used, max: lim.maxFilesPerContext, atCap: used >= lim.maxFilesPerContext }
}

/**
 * 这个文件能不能加进上下文。
 *
 * @returns `{ ok: true }` 或 `{ ok: false, reason: 'cap' | 'duplicate', max }`
 */
export function admitFileToContext({ file, contextFiles, pastedImages, limits }) {
  const list = Array.isArray(contextFiles) ? contextFiles : []
  if (!file) return { ok: false, reason: 'duplicate' }
  if (list.some((f) => String(f.id) === String(file.id))) {
    // 已经在里面了：不占新额度，也不该报「到顶」——那会让用户以为自己删错了东西
    return { ok: false, reason: 'duplicate' }
  }
  const quota = contextQuotaState({ contextFiles: list, pastedImages, limits })
  if (quota.atCap) return { ok: false, reason: 'cap', max: quota.max }
  return { ok: true }
}

/**
 * 这张粘贴/拖入的图片能不能收下。
 *
 * <p>张数与体积分开报：两种情况对用户是两句不同的话（少贴几张 / 压缩后重发）。
 * `size` 拿不到时不拦——「不知道多大」不等于「超限」。
 */
export function admitPastedImage({ size, pastedImages, contextFiles, limits }) {
  const lim = limits || DEFAULT_CONTEXT_LIMITS
  const images = Array.isArray(pastedImages) ? pastedImages.length : 0
  if (images >= lim.maxImagesPerTurn) {
    return { ok: false, reason: 'imageCount', max: lim.maxImagesPerTurn }
  }
  const bytes = typeof size === 'number' ? size : Number(size)
  if (Number.isFinite(bytes) && bytes > 0 && bytes > lim.maxImageBytes) {
    return { ok: false, reason: 'imageBytes', max: lim.maxImageBytes }
  }
  const quota = contextQuotaState({ contextFiles, pastedImages, limits: lim })
  if (quota.atCap) return { ok: false, reason: 'cap', max: quota.max }
  return { ok: true }
}

/** 给提示文案用：把字节数写成人话。 */
export function formatBytes(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '0 MB'
  if (n >= 1024 * 1024) return `${Math.round((n / (1024 * 1024)) * 10) / 10} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}
