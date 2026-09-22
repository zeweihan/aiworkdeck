// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 用户气泡上那份「这一轮带了哪些附件」的完整记录，以及按它重建请求用的 fileList
// （dev-board#793 K14 ④）。
//
// 病灶：气泡上的 contextFiles 原来只是 `{id, name, isDir}` 的精简副本，历史回灌出来的气泡
// 更是一个附件字段都没有。于是「重新生成」（K11，dev-board#790）只能传 `fileList: []`——
// 同一个问题重问一次，材料却没跟着走，模型当然给出不一样的答案，而用户以为这就是
// 「换一份回答」的正常波动。回退回填也一样：原文回到输入框了，@附件标签没回来。
//
// 单一事实来源：**气泡上的记录**。live 那条路由 handleSubmit 组装，历史那条路由
// GET /api/ai/history 的 attachments 还原，两者形状必须一致，否则「刷新前能重新生成、
// 刷新后不能」这种差别不会有任何东西报错。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/project-home/chat-attachments.test.mjs。

/** 与后端 ContextTurnSink 的 kind 同名：file / image / folder。 */
export const KIND_FILE = 'file'
export const KIND_IMAGE = 'image'
export const KIND_FOLDER = 'folder'

const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp']

/**
 * 判一个附件的 kind。**判据与后端必须同源**：后端 `isVisionCandidate` 是
 * 「fileType 优先、缺失退回文件名后缀」的双判据，这里照抄——kind 判错的代价是
 * 重新生成那一轮走了另一条分支（该直送的去了 OCR，或反过来）。
 */
export function attachmentKind(file) {
  if (!file) return KIND_FILE
  if (file.isDir === true || file.fileType === 'folder' || file.kind === KIND_FOLDER) return KIND_FOLDER
  if (file.kind === KIND_IMAGE) return KIND_IMAGE
  if (String(file.fileType || '').toLowerCase() === 'image') return KIND_IMAGE
  const name = String(file.name || file.fileName || '')
  const dot = name.lastIndexOf('.')
  if (dot >= 0 && dot < name.length - 1
      && IMAGE_EXTENSIONS.indexOf(name.slice(dot + 1).toLowerCase()) !== -1) {
    return KIND_IMAGE
  }
  return KIND_FILE
}

/**
 * 组一条挂在用户气泡上的完整附件记录。
 *
 * 比原来的 `{id, name, isDir}` 多出 fileType / wpsFileId / kind：前两个是重建请求要的
 * （fileType 参与后端判图的双判据），kind 是给界面用的（文件夹图标 / 图片角标）。
 */
export function attachmentRecord(file) {
  if (!file) return null
  const kind = attachmentKind(file)
  return {
    id: String(file.id),
    name: file.name || file.fileName || '',
    fileType: file.fileType || '',
    // 附件的 wpsFileId 从来没有随 contextItems 上送过（只有 activeContext 带它），
    // 所以历史还原出来的记录里没有这一项——这与当初真正发出去的请求完全一致。
    wpsFileId: file.wpsFileId || null,
    kind,
    isDir: kind === KIND_FOLDER,
  }
}

/** 历史回灌：GET /api/ai/history 的 attachments 行 → 气泡上的记录。 */
export function attachmentsFromHistory(rows) {
  if (!Array.isArray(rows)) return []
  return rows.map((row) => attachmentRecord({
    id: row.fileId,
    name: row.name,
    fileType: row.fileType,
    kind: row.kind,
  })).filter(Boolean)
}

/**
 * 按气泡上的记录重建一次请求用的 fileList（`sendMessage({ fileList })` 的形状）。
 *
 * <p>**重新生成与回退回填都必须走它**：同一个问题重问一次，带的材料要和当初一模一样，
 * 否则模型看到的输入变了，「换一份回答」就变成了「换一个问题」。
 * 形状与 handleSubmit 里组 `fileListToSend` 的那一段逐字对应
 *（`useAgentStream` 再把它映射成 contextItems：id / name / isDir / fileType）。
 */
export function fileListFromBubble(bubble) {
  const records = bubble && Array.isArray(bubble.contextFiles) ? bubble.contextFiles : []
  return records.filter((f) => f && f.id != null).map((f) => ({
    id: f.id,
    fileName: f.name || '',
    fileType: f.fileType || '',
    wpsFileId: f.wpsFileId || null,
    isDir: attachmentKind(f) === KIND_FOLDER,
  }))
}
