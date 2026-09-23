// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 对话里「改动 / 新增」卡片（SSE file_change）怎么落到一份文件上（dev-board#852）。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/project-home/chat-file-change-target.test.mjs。
//
// 病灶：doc_* / sheet_* / slide_* 改的是编辑器里当前打开的那份文档，后端原先把
// file_change 的文件名兜底成字面量 "Current Document"，卡片按名字去项目里找，
// 找不到就弹「未找到文件: Current Document」——用户刚看着 AI 改完的正是这份文件。
// 现在后端带上 fileId 并报真名；这里按 id 优先、名字其次去找。历史会话里落了库的
// 旧记录仍是 "Current Document"，与后端新口径「当前文档」（说不出是哪份时）一样，
// 当成「当前文档」处理：有活跃标签就切过去，没有就提示先打开文档。

// 后端说不出具体文件时的占位名：旧版字面量 + 现行口径（AgentOrchestrator.activeDocDisplayName(null)）。
export const CURRENT_DOC_SENTINELS = ['Current Document', '当前文档']

export function isCurrentDocSentinel(name) {
  return typeof name === 'string' && CURRENT_DOC_SENTINELS.indexOf(name.trim()) !== -1
}

function hasId(v) {
  return v !== null && v !== undefined && String(v).trim() !== ''
}

function sameId(a, b) {
  return hasId(a) && hasId(b) && String(a).trim() === String(b).trim()
}

function sameName(a, b) {
  return typeof a === 'string' && typeof b === 'string'
    && (a === b || a.toLowerCase() === b.toLowerCase())
}

/**
 * 两条 file_change 是不是同一处改动（useAgentStream 去重用）：
 * 两边都有 fileId 时按 id 比，否则退回按名字比；changeType 必须相同。
 */
export function isSameFileChange(a, b) {
  if (!a || !b || a.changeType !== b.changeType) return false
  if (hasId(a.fileId) && hasId(b.fileId)) return sameId(a.fileId, b.fileId)
  return a.fileName === b.fileName
}

/**
 * 卡片指的就是当前活跃标签吗？是的话只切过去，不重开、不拉文件列表。
 * 有 fileId 时只认 id；占位名（当前文档）只要有活跃标签就算命中；其余按名字比。
 */
export function matchesActiveTab({ name, fileId } = {}, activeTab) {
  if (!activeTab) return false
  if (hasId(fileId)) return sameId(fileId, activeTab.id)
  if (isCurrentDocSentinel(name)) return true
  return sameName(name, activeTab.name)
}

/**
 * 在项目文件列表里找卡片指的那份文件：fileId 优先，其次按名字。
 * 名字那一段是 handleOpenFileFromChat 原有的规则原样搬来：精确名 / 忽略大小写，
 * 找不到再按「基名 + 扩展名」（含 -draft）找一组产物，优先可编辑的那份。
 * 占位名（当前文档）不按名字找——项目里没有叫这个的文件，找到了也是巧合。
 */
export function findChatFile(files, { name, fileId } = {}) {
  const list = (Array.isArray(files) ? files : []).filter(f => f && !f.isFolder)
  if (hasId(fileId)) {
    const byId = list.find(f => sameId(f.id, fileId))
    if (byId) return byId
  }
  if (!name || isCurrentDocSentinel(name)) return null

  const exact = list.find(f => sameName(f.name, name))
  if (exact) return exact

  // 有些工具报上来的"变更文件名"其实是一组产物的基名而不是某一个文件：
  // 诉讼可视化的 file_change 带的是图名（litigation_render 的 diagramName），
  // 项目里真正存在的是同名文件夹下的 <图名>.drawio / .svg / .png。
  // 也认 -draft：语义地图未确认时引擎按设计给产物加这个后缀（草稿闸），
  // 而工具报上来的名字里没有它——第一次出图必然走这一支。
  const lower = name.toLowerCase()
  const bases = [lower + '.', lower + '-draft.']
  const candidates = list.filter(f =>
    typeof f.name === 'string' && bases.some(b => f.name.toLowerCase().startsWith(b)))
  // 一组产物里优先给可继续编辑的那份，其次是能看的母版。
  const rank = ['drawio', 'svg', 'png']
  return candidates.sort((a, b) => {
    const ra = rank.indexOf((a.fileType || '').toLowerCase())
    const rb = rank.indexOf((b.fileType || '').toLowerCase())
    return (ra < 0 ? rank.length : ra) - (rb < 0 ? rank.length : rb)
  })[0] || null
}
