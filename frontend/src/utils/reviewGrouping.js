// reviewGrouping.js — 审阅面板（ReviewPanel）的纯函数层（dev-board#377）。
//
// 面板里三个新维度全部在这里判定，组件只负责渲染与发命令：
//   1) 作者归类：AI WorkDeck / 当前登录用户 / 其他人——多方修订混在一份文档里时，
//      律师第一眼要能分清哪条是 AI 改的、哪条是自己改的。
//   2) 修订类型：引擎的 RedlineType 原样回传，这里归一成面板的显示键。旧实现
//      只分「Delete 与其余」，把格式类修订当成插入显示（错标）。
//   3) 修订理由：AI 把改动理由写成批注（正文常以「【修訂理由】」开头，前缀是
//      模型自己写的、不固定，所以**不按文本前缀识别，只按位置**）。批注锚区与
//      修订区间同段落且相交/相接，就认成这条修订的理由。
//
// 位置坐标（paraKey / start / end）由 worker 的 list_revisions / list_comments
// 如实回传；表格单元格、页眉页脚里的区间跨 story 无法与正文比较，worker 回
// paraKey:-1，这里一律不关联（宁可不挂，也不猜错）。

// AI 产生的修订与批注在引擎里的固定署名。worker 的 execCommand 按 __agent 切换
// 到这个名字（office_thread.js），改一处必须同步。
export const AI_AUTHOR = 'AI WorkDeck'

// 'ai' | 'me' | 'other'。selfAuthor 是宿主传给引擎 load_document 的 authorName
// （当前登录用户名），同源；拿不到用户名时任何非 AI 的作者都算「其他人」——
// 空名字不能当成「我」，否则未署名的修订会被误算到自己头上。
export function authorKind(author, selfAuthor) {
  const a = String(author == null ? '' : author).trim()
  if (a === AI_AUTHOR) return 'ai'
  const me = String(selfAuthor == null ? '' : selfAuthor).trim()
  if (me && a === me) return 'me'
  return 'other'
}

// 引擎 RedlineType 的取值不止这四种（还有 Style / TextTable / TableRowInsert…），
// 认不出的回 'other'，由组件原样显示引擎给的字符串——不猜，更不硬塞进「插入」。
const TYPE_KEYS = {
  Insert: 'insert',
  Delete: 'delete',
  Format: 'format',
  ParagraphFormat: 'paraFormat',
}
export function revisionTypeKey(type) {
  return TYPE_KEYS[String(type == null ? '' : type)] || 'other'
}

// 位置相接判据：同一段落（paraKey 必须 >= 0，-1 表示 worker 定位不到）+ 闭区间
// 相交。用闭区间是刻意的——页边显示模式下删除型修订在正文流里是零宽
// （start === end），挂在它紧前/紧后的批注只能靠「首尾相接」命中。
export function rangesTouch(a, b) {
  if (!a || !b) return false
  if (!(a.paraKey >= 0) || a.paraKey !== b.paraKey) return false
  return a.start <= b.end && b.start <= a.end
}

function locOf(x) {
  if (!x || !(x.paraKey >= 0)) return null
  const s = Number(x.start)
  const e = Number(x.end)
  if (!Number.isFinite(s) || !Number.isFinite(e)) return null
  return { paraKey: x.paraKey, start: Math.min(s, e), end: Math.max(s, e) }
}

// 批注 ↔ 修订的双向关联。返回两张 Map（键都是各自清单里的 index）：
//   reasons  修订 index → 挂在它上面的批注数组（顺序即批注清单顺序）
//   linked   批注 index → 它挂到的修订 index 数组
// 一条批注可以同时挂到多条修订上（一次替换 = 一删一插，理由是同一条），这是
// 有意的：两张卡片都要显示理由，任一张被处置都能把这条批注标记为已解决。
export function linkCommentsToRevisions(revisions, comments) {
  const reasons = new Map()
  const linked = new Map()
  const revs = Array.isArray(revisions) ? revisions : []
  const cmts = Array.isArray(comments) ? comments : []
  for (const c of cmts) {
    const cl = locOf(c)
    if (!cl) continue
    for (const r of revs) {
      const rl = locOf(r)
      if (!rangesTouch(cl, rl)) continue
      if (!reasons.has(r.index)) reasons.set(r.index, [])
      reasons.get(r.index).push(c)
      if (!linked.has(c.index)) linked.set(c.index, [])
      linked.get(c.index).push(r.index)
    }
  }
  return { reasons, linked }
}

// 引擎按「一次编辑操作」记一条 redline：连按 Backspace 删掉一个词就是一字一条。
// 面板把**位置上首尾相接、且同类型同作者同分钟**的相邻条目并成一张卡片。
//
// 三个新维度进合并判据的口径（dev-board#377）：
//   - 作者：早就在判据里（字符串相等），不同作者绝不合并；
//   - 类型：判据用的是引擎原串 r.type，粒度只会变细不会变粗——原来 Format 与
//     Insert 显示成同一种但 type 串本就不同，合并行为与改造前逐条一致；
//   - 理由：**不进合并判据**。理由是批注在旁边的附注，让它左右分组会把「一次
//     替换产生的删+插」这类本该相邻的条目切碎。整组的 reasons 取组内全部条目
//     关联到的批注去重后的并集。
export function groupRevisions(revisions, opts) {
  const o = opts || {}
  const reasons = o.reasons || new Map()
  const selfAuthor = o.selfAuthor
  const textLimit = o.textLimit == null ? 120 : o.textLimit
  const groups = []
  for (const r of (Array.isArray(revisions) ? revisions : [])) {
    const last = groups[groups.length - 1]
    const joins = last && r.contiguous
      && last.type === r.type
      && (last.author || '') === (r.author || '')
      && (last.date || '') === (r.date || '')
    if (joins) {
      last.items.push(r)
      last.text += (r.text || '')
    } else {
      groups.push({
        key: 'g' + r.index,
        type: r.type,
        typeKey: revisionTypeKey(r.type),
        description: r.description || '',
        author: r.author,
        authorKind: authorKind(r.author, selfAuthor),
        date: r.date,
        inTable: r.inTable,
        paragraph: r.paragraph,
        text: r.text || '',
        items: [r],
      })
    }
  }
  for (const g of groups) {
    if (textLimit > 0 && g.text.length > textLimit) g.text = g.text.slice(0, textLimit) + '…'
    const seen = new Set()
    g.reasons = []
    for (const it of g.items) {
      for (const c of (reasons.get(it.index) || [])) {
        const k = c.id != null && c.id !== '' ? 'i' + c.id : 'x' + c.index
        if (seen.has(k)) continue
        seen.add(k)
        g.reasons.push(c)
      }
    }
  }
  return groups
}

// 四个筛选桶的计数。**恒按未筛选的全量算**（与底稿面板同口径）：切了筛选之后
// 其余桶的数字不能跟着变，否则用户没法用它判断「还有几条别人的改动没看」。
export function countByAuthorKind(groups) {
  const c = { all: 0, ai: 0, me: 0, other: 0 }
  for (const g of (Array.isArray(groups) ? groups : [])) {
    c.all++
    if (c[g.authorKind] != null) c[g.authorKind]++
  }
  return c
}

export function filterByAuthorKind(groups, kind) {
  const gs = Array.isArray(groups) ? groups : []
  if (!kind || kind === 'all') return gs
  return gs.filter((g) => g.authorKind === kind)
}
