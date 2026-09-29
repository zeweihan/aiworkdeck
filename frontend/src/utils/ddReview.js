// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// 尽调清单条目的审核呈现（dev-board#1057）。状态机的权威在后端 DdService.updateItemStatus：
//   PENDING 待上传 → 客户上传 → UPLOADED 待审核 → 律师「通过」APPROVED / 「驳回」REJECTED（带理由）
//   APPROVED 可「撤回通过」回 UPLOADED；REJECTED 由客户重传回到 UPLOADED。
// 这里只决定每个视角下显示什么徽标、给哪些按钮，与后端的放行口径保持一致。

export const DD_STATUS_LABEL_KEYS = {
  PENDING: 'panels.ddItemPending',
  UPLOADED: 'panels.ddItemUploaded',
  APPROVED: 'panels.ddItemApproved',
  REJECTED: 'panels.ddItemRejected'
}

export function ddStatusLabelKey(status) {
  return DD_STATUS_LABEL_KEYS[status] || DD_STATUS_LABEL_KEYS.PENDING
}

/** 律师（非客户视角）在这一条上能做的审核动作；没有附件的条目不给任何审核按钮。 */
export function ddReviewActions(item, clientView) {
  if (clientView || !item || !item.uploadedFileId) return []
  if (item.status === 'UPLOADED') return ['approve', 'reject']
  if (item.status === 'APPROVED') return ['withdraw']
  return []
}

/** 能不能再传材料：已通过的条目不收（后端同样 400），其余都能传；驳回后就是重传。 */
export function ddCanUpload(item) {
  return !!item && item.status !== 'APPROVED'
}

/** 清单级汇总：待审核 / 已通过 / 已驳回各几项。 */
export function ddReviewSummary(items) {
  const out = { uploaded: 0, approved: 0, rejected: 0 }
  for (const it of items || []) {
    if (it.status === 'UPLOADED') out.uploaded++
    else if (it.status === 'APPROVED') out.approved++
    else if (it.status === 'REJECTED') out.rejected++
  }
  return out
}

/** 目标状态：审核动作 → 发给 updateDdItemStatus 的 status。 */
export function ddActionTarget(action) {
  return { approve: 'APPROVED', reject: 'REJECTED', withdraw: 'UPLOADED' }[action] || null
}
