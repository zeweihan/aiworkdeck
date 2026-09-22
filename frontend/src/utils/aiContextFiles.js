// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「把项目文件挂进 AI 上下文」这件事的公共判据（dev-board#794）。
 *
 * 四条路径共用这里：文件树拖拽、文件树右键「加入 AI 对话」、输入框 `@` 引用选择器、
 * 「+」对话框的「从项目选择」页签。检索排序与文件夹上限各写一份的话，同一个文件在
 * 四个入口会得到四种结果——而且四种都不报错。
 */

import { HIDDEN_SYSTEM_FOLDER_NAMES } from './fileTreeBuild.js'

/** 文件夹整体挂进上下文时的后代文件数上限（超了只提示，不截断） */
export const AI_CONTEXT_FOLDER_FILE_LIMIT = 10

/** 选择器一次最多列多少条（照 QuickOpenPanel 的既有口径） */
export const MENTION_MAX_RESULTS = 30

/**
 * 递归数一个文件夹下的文件数（不含文件夹自身与子文件夹）。
 * parentId 在不同来源里可能是数字或字符串，一律用宽松比较。
 */
export function countDescendantFiles(allFiles, folderId) {
  if (!Array.isArray(allFiles)) return 0
  const countIn = (pid, depth) => {
    if (depth > 20) return 0
    let count = 0
    for (const f of allFiles) {
      // eslint-disable-next-line eqeqeq
      if (f.parentId != pid) continue
      if (f.isFolder || f.isDir) count += countIn(f.id, depth + 1)
      else count += 1
    }
    return count
  }
  return countIn(folderId, 0)
}

/**
 * 剔除系统文件夹**及其整棵子树**。文件树那边只要不展示那个文件夹就够了，
 * 这里是一份扁平清单——只跳过文件夹本身的话，暂存区里的文件仍会一条条列出来
 * （真机实测：「从项目选择」页签第一行就是 `__staging_area__`）。
 */
export function excludeSystemFolders(files) {
  const list = Array.isArray(files) ? files : []
  const hiddenIds = new Set()
  for (const f of list) {
    if (f && HIDDEN_SYSTEM_FOLDER_NAMES.has(f.name)) hiddenIds.add(String(f.id))
  }
  if (!hiddenIds.size) return list.slice()
  // 子树可能有多层，逐轮把父节点已隐藏的也标上，直到不再新增
  let grew = true
  while (grew) {
    grew = false
    for (const f of list) {
      if (!f || f.parentId == null) continue
      const id = String(f.id)
      if (hiddenIds.has(id)) continue
      if (hiddenIds.has(String(f.parentId))) {
        hiddenIds.add(id)
        grew = true
      }
    }
  }
  return list.filter((f) => f && !hiddenIds.has(String(f.id)))
}

/**
 * 按名字模糊匹配：前缀命中排在包含命中之前，各自保持传入顺序。
 * 空查询原样返回前 max 条（「刚敲下 @ 就先看到些东西」比空列表有用）。
 */
export function matchProjectFiles(files, query, max = MENTION_MAX_RESULTS) {
  const list = Array.isArray(files) ? files : []
  const q = String(query || '').trim().toLowerCase()
  if (!q) return list.slice(0, max)
  const starts = []
  const includes = []
  for (const f of list) {
    const name = String(f.name || '').toLowerCase()
    if (name.startsWith(q)) starts.push(f)
    else if (name.includes(q)) includes.push(f)
    if (starts.length >= max) break
  }
  return starts.concat(includes).slice(0, max)
}

/** 面包屑（「合同 / 附件」），用于在同名文件之间区分 */
export function dirLabelOf(file, byId) {
  const parts = []
  let pid = file && file.parentId
  let depth = 0
  while (pid && depth < 10) {
    const parent = byId.get(pid)
    if (!parent) break
    parts.unshift(parent.name)
    pid = parent.parentId
    depth++
  }
  return parts.join(' / ')
}
