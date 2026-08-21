// evidenceStaleQueue.js — 改字 stale 提示条的合并规则（spec §4.4，纯函数、可测）。
//
// - 同一 linkKey 在 windowMs（默认 3s）内只弹一次；
// - 多条同时 stale 在 flush 时合并成一批，由提示条决定单条/多条展示；
// - ignore(linkKey) = 本会话不再为该 linkKey 弹，状态仍是 stale（面板照常亮黄）。

export class StaleQueue {
  constructor({ now = () => Date.now(), windowMs = 3000 } = {}) {
    this.now = now
    this.windowMs = windowMs
    this.ignored = new Set()
    this.lastShown = new Map()
    this.pending = new Map()
  }

  /** 入队；被忽略 / 窗口内重复 → false。 */
  offer(linkKey, text) {
    if (this.ignored.has(linkKey)) return false
    const t = this.now()
    // 「从没弹过」与「在时刻 0 弹过」要分开：用 has 判，不能 `|| 0`
    if (this.lastShown.has(linkKey) && t - this.lastShown.get(linkKey) < this.windowMs) return false
    this.pending.set(linkKey, text)
    return true
  }

  /** 取出待弹的全部条目并记为已弹。 */
  flush() {
    const items = [...this.pending.entries()].map(([linkKey, text]) => ({ linkKey, text }))
    const t = this.now()
    for (const i of items) this.lastShown.set(i.linkKey, t)
    this.pending.clear()
    return items
  }

  ignore(linkKey) {
    this.ignored.add(linkKey)
    this.pending.delete(linkKey)
  }

  isIgnored(linkKey) {
    return this.ignored.has(linkKey)
  }
}
