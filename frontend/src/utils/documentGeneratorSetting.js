// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { getDocumentGeneratorSettings } from '@/services/api.js'

/**
 * 「保存时要不要给文档打产品标识」的一次性缓存（可溯源性设计规范附录 B4）。
 *
 * 保活池里同时活着好几个 LibreOfficeEditor 实例，每一次自动保存都要问一次开关，
 * 所以缓存放在模块级、按整个会话只拉一次。
 *
 * 读不到就当**关**（返回 null，不打标、不报错）：这是保存链路，宁可少一个可选的
 * 元数据字段，也不能因为一个设置项读不到就让保存报错或卡住。**只有读成功才入缓存**
 * ——后端刚起来的那几秒读不到是常态，缓存住的话这一整个会话都不再打标了。
 */
let cachedPromise = null

/** @returns {Promise<string|null>} 要写的 Application 串；null = 不打标 */
export function documentStampApplication() {
  if (cachedPromise) return cachedPromise
  const p = (async () => {
    const res = await getDocumentGeneratorSettings()
    if (!res || res.code !== 0) throw new Error('document generator settings unavailable')
    if (!res.enabled) return null
    const app = typeof res.application === 'string' ? res.application.trim() : ''
    return app || null
  })().catch(() => {
    if (cachedPromise === p) cachedPromise = null // 读失败不入缓存，下次保存再试
    return null
  })
  cachedPromise = p
  return p
}

/** 设置页改完开关后调用，让下一次保存重新问一次。 */
export function resetDocumentStampCache() {
  cachedPromise = null
}
