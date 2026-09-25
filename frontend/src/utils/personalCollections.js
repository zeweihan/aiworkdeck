// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 设置页「个人」组里「全部收藏」「我的待办」两栏的纯函数（dev-board#872）。
// 零依赖（不 import @/ 别名、Vue、uni；唯一的相对导入 taskUtils.js 同样零依赖），好让 node --test 直接导入：
//   cd frontend && npm run test:project-home

/**
 * 收藏的类型键：'web' | 'image' | 'text'。
 * 判定顺序与工作台左栏 ProjectFavoritesPanel 一致——有来源 URL 就算网页（网页截图也算），
 * 否则有图算图片，其余是文本摘录。两处共用这一份。
 */
export function favoriteKind(fav) {
  if (fav && fav.sourceUrl) return 'web'
  if (fav && fav.imagePath) return 'image'
  return 'text'
}

/** 来源域名：后端 meta 里抽出的 sourceHost 优先，没有再从 sourceUrl 解析。 */
export function favoriteHost(fav) {
  if (!fav) return ''
  if (fav.sourceHost) return String(fav.sourceHost)
  if (!fav.sourceUrl) return ''
  try {
    return new URL(fav.sourceUrl).host
  } catch (e) {
    return ''
  }
}

/** 本地过滤：标题 / 内容 / 来源 URL / 项目名，子串匹配、忽略大小写。空查询原样返回。 */
export function filterFavorites(list, query) {
  const items = Array.isArray(list) ? list : []
  const q = String(query || '').trim().toLowerCase()
  if (!q) return items
  return items.filter((fav) => {
    const hay = [fav.title, fav.content, fav.sourceUrl, fav.sourceHost, fav.projectName]
      .filter(Boolean).join('\n').toLowerCase()
    return hay.includes(q)
  })
}

/**
 * 按项目分组。组的先后按「该项目最近一条收藏」排（后端已按 createdAt 倒序给，
 * 这里保持首次出现顺序即可），组内保持原顺序。
 * 没有 projectId 或项目名查不到（项目已删）的归到 key 为 'none' 的一组，排在最后。
 * 返回 [{ key, projectId, projectName, items }]，projectName 可能为 null，由调用方给兜底文案。
 */
export function groupFavoritesByProject(list) {
  const groups = new Map()
  let orphan = null
  for (const fav of Array.isArray(list) ? list : []) {
    const pid = fav && fav.projectId != null ? fav.projectId : null
    if (pid == null || !fav.projectName) {
      if (!orphan) orphan = { key: 'none', projectId: pid, projectName: null, items: [] }
      orphan.items.push(fav)
      continue
    }
    const key = String(pid)
    if (!groups.has(key)) groups.set(key, { key, projectId: pid, projectName: fav.projectName, items: [] })
    groups.get(key).items.push(fav)
  }
  const out = [...groups.values()]
  if (orphan) out.push(orphan)
  return out
}

// 待办分组与本地日期键已并入事项系统的唯一出处 components/calendar/taskUtils.js
// （dev-board#896），这里只 re-export，保持既有调用方与测试的导入路径不变。
// 用相对路径而不是 @/ 别名：node --test 直接导入本文件时不认别名。
export { localDateKey, groupTodos } from '../components/calendar/taskUtils.js'

/** 新增待办时能选的项目：只留当前用户能写的（与后端 TaskController.requireWrite 同口径）。 */
const WRITABLE_ROLES = new Set(['OWNER', 'MANAGER', 'ADMIN', 'PARTICIPANT'])
export function writableProjects(projects) {
  return (Array.isArray(projects) ? projects : []).filter((p) => p && WRITABLE_ROLES.has(p.myRole))
}
