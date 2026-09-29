// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 欢迎标签「Recent」一栏的取数规则（dev-board#1047）。零依赖纯函数，node --test 直接导入。
//
// 数据源两份：utils/recentProjects.js 的最近打开 id（只存 id、顺序即最近度，最多 8 个）
// 与 getMyProjects 的全量清单（名称一律实时解析，改名不陈旧）。
//
// 规则：
//   1. 先按「最近打开」顺序排，清单里已经没有的 id（删了 / 换了本机身份）静默丢掉；
//   2. 最近打开不足 8 条时，用其余项目按最近活动时间倒序补齐——新装的机器 / 清过缓存的
//      用户手上明明有案卷，Recent 却是空的，那是欢迎页在撒谎；
//   3. 总数不超过 max（默认 8）。

export const RECENT_MAX = 8

function activityTime(p) {
  const raw = (p && (p.lastActivityAt || p.updatedAt || p.createdAt)) || ''
  const t = Date.parse(raw)
  return Number.isFinite(t) ? t : 0
}

/**
 * @param {Array<{id:number|string,name:string,lastActivityAt?:string}>} projects getMyProjects 的结果
 * @param {Array<number>} recentIds 最近打开的项目 id（越靠前越近）
 * @param {number} [max]
 * @returns {Array} 项目对象（原样返回，不复制字段）
 */
export function buildRecentProjects(projects, recentIds, max = RECENT_MAX) {
  const list = Array.isArray(projects) ? projects.filter((p) => p && p.id != null) : []
  const byId = new Map(list.map((p) => [Number(p.id), p]))
  const out = []
  const seen = new Set()
  for (const raw of Array.isArray(recentIds) ? recentIds : []) {
    const id = Number(raw)
    const p = byId.get(id)
    if (!p || seen.has(id)) continue
    out.push(p)
    seen.add(id)
    if (out.length >= max) return out
  }
  const rest = list
    .filter((p) => !seen.has(Number(p.id)))
    .sort((a, b) => activityTime(b) - activityTime(a))
  for (const p of rest) {
    if (out.length >= max) break
    out.push(p)
  }
  return out
}
