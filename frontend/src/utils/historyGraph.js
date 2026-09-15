// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 提交历史的泳道图排版（dev-board#624）——**纯函数，不许 import 任何东西**。
 *
 * 与 utils/memberLookup.js 同一条纪律：一旦引到 '@/xxx' 别名，node --test 解析不了，
 * 这套测试就只能退化成源码字符串断言。SVG 渲染留在 CommitHistoryTab.vue 里，
 * 这里只出坐标（lane 序号）与连线（从哪条泳道拐到哪条），单测在
 * frontend/tests/version-history/historyGraph.test.mjs。
 *
 * 输入是 `/version/history` 的 entries：**从新到旧**排列，每条带 sha / parents / refs
 * （refs 是分支尖端标签 [{type:'mainline'|'draft'|'remote'|'local', name}]）与 remote 位。
 *
 * 排版口径（就是 `git log --graph` 那一套，只是换了词）：
 * · 每条提交占一条泳道 lane（0 起，越小越靠左；主线在 0）。
 * · 一条「线」= 一条 child→parent 的边。边在**被指向的那个 parent 被预定的时刻**
 *   确定自己的泳道，此后一直待在那条泳道里，直到画到 parent 那一行为止。
 * · 所以一条边只在它的起点（child 那一行）可能拐弯：
 *   - 拐到右边（新开的泳道）= fork，画「从这一版另起一稿」那种分叉；
 *   - 拐到左边（parent 早被别的 child 预定过了）= merge，两条线在 parent 处汇合；
 *   - 不拐 = straight。
 * · 「双亲跨多行」自然成立：合并提交的第二个双亲可能在好几行之后才出现，
 *   中间每一行都记一条 straight 的过路线，线不会断。
 *
 * 找不到父提交的边（分页窗口之外）直接不画：画一条通向空白的线比不画更难解释。
 */

/** refs/remote 位 → 颜色键。返回 '' 表示这一行自己说不出颜色，跟着泳道走。 */
function explicitColor(entry) {
  if (!entry) return ''
  if (entry.remote) return 'remote'
  const refs = Array.isArray(entry.refs) ? entry.refs : []
  for (const type of ['remote', 'draft', 'mainline']) {
    if (refs.some((r) => r && r.type === type)) return type
  }
  return ''
}

/**
 * @param {Array} entries `/version/history` 的 entries（从新到旧）
 * @returns {Array} 每条 entry 一行：{ sha, lane, colorKey, connections: [{fromLane, toLane, kind, colorKey}] }
 *                  connections 描述的是**这一行与下一行之间**那段带子里的所有线段。
 */
export function layoutGraph(entries) {
  const list = Array.isArray(entries) ? entries.filter((e) => e && e.sha) : []
  if (!list.length) return []

  const indexOfSha = new Map()
  list.forEach((e, i) => { if (!indexOfSha.has(e.sha)) indexOfSha.set(e.sha, i) })

  // 已预定但还没画到的边：sha -> { lane, color }。一个 sha 只预定一次泳道
  // （多个孩子指向同一个父提交时，后来的那些都汇进这一条）。
  const reserved = new Map()
  // 泳道占用表：lanes[i] = 占着这条泳道的那个 sha（null = 空闲）
  const lanes = []
  const laneColor = []

  const firstFreeLane = () => {
    for (let i = 0; i < lanes.length; i++) if (lanes[i] == null) return i
    lanes.push(null)
    return lanes.length - 1
  }
  const takeLane = (i, sha) => { lanes[i] = sha }

  const rows = []

  for (let row = 0; row < list.length; row++) {
    const entry = list[row]
    const sha = entry.sha

    // 1) 这一行落在哪条泳道：已经被孩子预定过就用那条，否则新开一条。
    let lane
    const booked = reserved.get(sha)
    if (booked) {
      lane = booked.lane
      reserved.delete(sha)
    } else {
      lane = firstFreeLane()
    }
    takeLane(lane, sha)

    // 2) 颜色：自己说得出（带 ref / remote）就用自己的，否则继承泳道。
    const colorKey = explicitColor(entry) || laneColor[lane] || (lane === 0 ? 'mainline' : 'draft')
    laneColor[lane] = colorKey

    // 3) 这一行长出去的边：每个父提交一条。
    //    第一个父提交优先留在本行这条泳道上（主线一路直下）。
    const connections = []
    const parents = Array.isArray(entry.parents) ? entry.parents.filter(Boolean) : []
    lanes[lane] = null // 本行画完，泳道先释放，第一个父提交多半立刻收回去

    for (const parentSha of parents) {
      if (!indexOfSha.has(parentSha)) continue // 父提交在分页窗口之外，这条边不画
      const existing = reserved.get(parentSha)
      if (existing) {
        // 父提交早被别的孩子预定了：这条边拐过去汇合
        connections.push({
          fromLane: lane,
          toLane: existing.lane,
          kind: existing.lane === lane ? 'straight' : 'merge',
          colorKey: laneColor[existing.lane] || colorKey,
        })
        continue
      }
      let parentLane
      if (lanes[lane] == null && !connections.some((c) => c.toLane === lane)) {
        parentLane = lane // 本行这条泳道还空着，第一个父提交直接接上
      } else {
        parentLane = firstFreeLane()
      }
      takeLane(parentLane, parentSha)
      reserved.set(parentSha, { lane: parentLane })
      if (laneColor[parentLane] == null) laneColor[parentLane] = colorKey
      connections.push({
        fromLane: lane,
        toLane: parentLane,
        kind: parentLane === lane ? 'straight' : (parentLane > lane ? 'fork' : 'merge'),
        colorKey: laneColor[parentLane] || colorKey,
      })
    }

    // 4) 过路线：别的行留下、这一行还没画到的边，在这段带子里直上直下。
    for (const [parentSha, info] of reserved) {
      if (parentSha === sha) continue
      if (connections.some((c) => c.toLane === info.lane)) continue
      connections.push({
        fromLane: info.lane, toLane: info.lane, kind: 'straight',
        colorKey: laneColor[info.lane] || colorKey,
      })
    }
    connections.sort((a, b) => a.fromLane - b.fromLane || a.toLane - b.toLane)

    rows.push({ sha, lane, colorKey, connections })
  }

  return rows
}

/** 画布要几条泳道宽（含连线拐到的那条）。 */
export function laneCountOf(rows) {
  let max = 0
  for (const r of rows || []) {
    if (r.lane > max) max = r.lane
    for (const c of r.connections || []) {
      if (c.fromLane > max) max = c.fromLane
      if (c.toLane > max) max = c.toLane
    }
  }
  return max + 1
}
