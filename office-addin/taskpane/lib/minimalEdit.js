/**
 * 字符级最小编辑差分（纯函数模块，不依赖 Office.js，便于单测）。
 *
 * 用途：Word 原生修订（TrackAll）下把整段 Range 直接 insertText(replace)
 * 会记成「整段删除 + 整段插入」——「我爱你」改「我恨你」在修订面板里读作
 * 删「我爱你」加「我恨你」。本模块算出字符级最小编辑段，调用方只对差异段
 * 落笔，修订面板里就只剩删「爱」加「恨」。
 *
 * 口径与桌面端 LOWA 的 `frontend/src/zetaoffice/public/office_thread.js`
 * 中的 minimalEdits 一致（PR#188）：公共前后缀先裁剪 → 中段有界 LCS
 * （超界回退整段单编辑）→ 合并被单个巧合相同字符夹住的相邻编辑。
 * **改一边要想到另一边**：两处算法口径必须保持一致，否则同一处修改在
 * 桌面编辑器与 Office 插件里会呈现出不同颗粒度的修订。
 *
 * 与 LOWA 版的唯一差异是返回值形态：
 *   - LOWA 版返回 `{start, delLen, insText}` 且**按 start 降序**（它在
 *     UNO 里靠字符偏移走光标，降序即"从右到左"的应用顺序）；
 *   - 本版返回 `{start, end, oldText, newText}` 且**按 start 升序**
 *     （Office.js 没有按偏移切 Range 的 API，调用方要用 oldText 段当锚串
 *     二次 search 定位，升序更便于携带上下文消歧；应用时自行倒序遍历）。
 * 编辑段互不重叠。
 */

// 中段 LCS 的 DP 单元上限（500x500 字符，远超任何单条条款的改动量）；
// 超过就退回"整个中段一次替换"——已经足够小，且不值得为它跑 DP。
const LCS_CELL_LIMIT = 250000

/**
 * 计算把 oldStr 变成 newStr 的最小编辑段。
 *
 * @param {string} oldStr 原文
 * @param {string} newStr 新文
 * @returns {Array<{start:number, end:number, oldText:string, newText:string}>}
 *   start/end 为 oldStr 内的字符偏移（左闭右开）；oldStr[start,end) 这一段被
 *   newText 取代。纯插入时 start === end 且 oldText 为空；纯删除时 newText
 *   为空。结果按 start 升序、互不重叠；两串相同时返回空数组。
 */
export function minimalEdits(oldStr, newStr) {
  const o = oldStr == null ? '' : String(oldStr)
  const n = newStr == null ? '' : String(newStr)
  const oLen = o.length
  const nLen = n.length

  // 1) 裁掉公共前缀与公共后缀（两者不许重叠）
  const maxP = Math.min(oLen, nLen)
  let p = 0
  while (p < maxP && o.charCodeAt(p) === n.charCodeAt(p)) p++
  let s = 0
  while (s < maxP - p && o.charCodeAt(oLen - 1 - s) === n.charCodeAt(nLen - 1 - s)) s++

  const oMid = o.slice(p, oLen - s)
  const nMid = n.slice(p, nLen - s)
  if (!oMid && !nMid) return []

  // 2) 纯插入 / 纯删除 / 中段过大：一条连续替换已经是最小可用形态
  if (!oMid || !nMid || oMid.length * nMid.length > LCS_CELL_LIMIT) {
    return [toEdit(o, p, oMid.length, nMid)]
  }

  // 3) 中段跑 LCS DP（长度 <= 500，Uint16 足够）
  const m = oMid.length
  const q = nMid.length
  const W = q + 1
  const dp = new Uint16Array((m + 1) * W)
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= q; j++) {
      dp[i * W + j] = oMid.charCodeAt(i - 1) === nMid.charCodeAt(j - 1)
        ? dp[(i - 1) * W + (j - 1)] + 1
        : Math.max(dp[(i - 1) * W + j], dp[i * W + (j - 1)])
    }
  }

  // 4) 从尾回溯，把相邻的删+插合并成一条替换。回溯天然产出降序 start。
  const runs = []
  let i = m
  let j = q
  let curDel = 0
  let curIns = ''
  const flush = (atOld) => {
    if (curDel || curIns) {
      runs.push({ start: p + atOld, delLen: curDel, insText: curIns })
      curDel = 0
      curIns = ''
    }
  }
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oMid.charCodeAt(i - 1) === nMid.charCodeAt(j - 1)) {
      flush(i)
      i--
      j--
    } else if (j > 0 && (i === 0 || dp[i * W + (j - 1)] >= dp[(i - 1) * W + j])) {
      curIns = nMid.charAt(j - 1) + curIns
      j--
    } else {
      curDel++
      i--
    }
  }
  flush(0)

  // 5) 收尾：被"单个巧合相同字符"夹住的两条编辑合成一条（LCS 在中文里常
  //    捞到"的/、"这类巧合字，拆开读起来是零碎又费解的修订）。
  //    runs 是降序，[k+1] 是左邻。
  for (let k = 0; k + 1 < runs.length;) {
    const right = runs[k]
    const left = runs[k + 1]
    const gap = right.start - (left.start + left.delLen)
    if (gap >= 0 && gap <= 1) {
      left.insText = left.insText + o.slice(left.start + left.delLen, right.start) + right.insText
      left.delLen = left.delLen + gap + right.delLen
      runs.splice(k, 1)
    } else k++
  }

  // 6) 转成升序的 {start, end, oldText, newText}
  const edits = []
  for (let k = runs.length - 1; k >= 0; k--) {
    edits.push(toEdit(o, runs[k].start, runs[k].delLen, runs[k].insText))
  }
  return edits
}

function toEdit(o, start, delLen, insText) {
  const end = start + delLen
  return { start, end, oldText: o.slice(start, end), newText: insText }
}
