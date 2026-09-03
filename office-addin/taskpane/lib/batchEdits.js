/**
 * 批量改写（office_replace_batch）的入参契约（dev-board#419）。
 *
 * 两个家族各有自己的落笔实现（officeExecutor 走 Office.js 的排队+sync，
 * wpsWordHandlers 走 JSAPI 的同步偏移直切），但**入参校验必须是同一份**——
 * 后端只有一个 office_replace_batch 工具描述，两边对模型说的话不能有出入。
 */

/**
 * 一批最多改多少处。上限不是性能约束（批量路径的过桥量与 N 无关），
 * 而是**模型侧**的约束：一次工具调用的参数越长，越容易撞上单次输出长度上限
 * 被截断（编排器的 isTruncatedToolCallRound 会把整轮工具调用丢弃）。
 * 整篇校对分两三批交是稳的。
 */
export const MAX_BATCH_ITEMS = 50

/** Word 的查找串上限（与 officeExecutor.WORD_SEARCH_MAX_CHARS 同值） */
const SEARCH_MAX_CHARS = 255

/**
 * 批量入参归一 + **全部前置校验**：任何一条不合法都整批拒绝，一个字都不写。
 * 校验发生在进入宿主 API 之前，失败时连修订开关都没动过。
 *
 * @returns {Array<{index:number, searchText:string, replaceText:string}>} index 从 1 起
 */
export function normalizeBatchItems(args) {
  const raw = Array.isArray(args && args.items) ? args.items : null
  if (!raw || !raw.length) {
    throw new Error('items 不能为空：请传入 [{searchText, replaceText}, ...]')
  }
  if (raw.length > MAX_BATCH_ITEMS) {
    throw new Error(`一批最多 ${MAX_BATCH_ITEMS} 处，本次给了 ${raw.length} 处，请拆成多批`)
  }
  const items = []
  const seen = new Set()
  raw.forEach((it, i) => {
    const index = i + 1
    const searchText = String((it && it.searchText) || '')
    const replaceText = it && it.replaceText != null ? String(it.replaceText) : ''
    if (!searchText) throw new Error(`第 ${index} 条的 searchText 为空`)
    if (/[\r\n]/.test(searchText)) {
      throw new Error(`第 ${index} 条的 searchText 跨段落（含换行），查找不支持跨段匹配。`
        + '请把它拆成同一段内的多条')
    }
    if (searchText.length > SEARCH_MAX_CHARS) {
      throw new Error(`第 ${index} 条的 searchText 超过 ${SEARCH_MAX_CHARS} 字（查找上限），请缩短`)
    }
    // 两条相同的 searchText 会指向同一处，落笔两遍——整批拒绝比事后解释便宜
    if (seen.has(searchText)) {
      throw new Error(`第 ${index} 条的 searchText 与前面某条重复（同一处会被改两遍），请合并成一条`)
    }
    seen.add(searchText)
    items.push({ index, searchText, replaceText })
  })
  // 一条 searchText 是另一条的子串时，两处命中必然重叠——各自落笔就是把同一段文字
  // 改两遍（后一笔盖在前一笔的产物上），结果是乱码而不是报错。校对场景里模型很容易
  // 同时给出「违约责仁」和「承担违约责仁。」这样一短一长的两条，所以必须拦。
  for (const a of items) {
    for (const b of items) {
      if (a.index === b.index) continue
      if (a.searchText.includes(b.searchText)) {
        throw new Error(`第 ${b.index} 条的 searchText 是第 ${a.index} 条的一部分，两处会重叠、同一段文字被改两遍。`
          + '请合并成一条，或把两条都换成互不包含的原文')
      }
    }
  }
  return items
}

/** 失败条目按原始序号回报——模型据此只重试失败的那几条 */
export function sortByIndex(list) {
  return list.slice().sort((a, b) => a.index - b.index)
}
