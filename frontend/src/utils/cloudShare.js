// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「把这份案卷放进团队案件库」的连接选取规则——**唯一来源**。
 *
 * 原先只长在 CollabDialog.onShare 里；加同事的弹窗也需要同一个按钮（案卷没进库时
 * 加谁都没有意义），复制一份的话两处必然慢慢分叉，而分叉的后果不是界面难看：
 * 拿错 connectionId 会把客户材料推去用户没选的服务器。
 *
 * 有意不 import '@/services/api.js'：调用方注入 share 函数，这个模块才跑得进
 * node --test（tests/member-invite/cloud-share.test.mjs），也才好在测试里断言
 * 「多于一条时一次都不许调 share」。
 */

/** 多于一条连接时的拒绝理由（调用方据此选文案：version.tooManyLibraries）。 */
export const TOO_MANY_LIBRARIES = 'TOO_MANY_LIBRARIES'

/**
 * 选出这次该用哪条连接。
 *
 * 本机只认一个案件库：官方那个，或 cloud.collab.base-url 指过来的自建库。
 * 恰好只有一条连接时指名用它（省掉一次重新桥接）；没有连接时回 null，
 * 调用方据此**不传** connectionId，让后端连官方案件库再共享。
 * 多于一条（只可能是运维经 API 连出来的历史状态，界面上已无从消歧义）时直接拒绝
 * ——绝不「拿列表第一条」也绝不静默改推官方：前者会拿着失效令牌去推一个早已不在的
 * 服务器，后者会把案卷推去用户没选的地方。
 */
export function pickShareConnectionId(connections) {
  const list = Array.isArray(connections) ? connections : []
  if (list.length > 1) return { ok: false, reason: TOO_MANY_LIBRARIES }
  return { ok: true, connectionId: list.length === 1 ? list[0].id : null }
}

/**
 * 放进案件库。share 由调用方注入（真实调用方传 api.js 的 shareProjectToCloud）。
 *
 * 回 `{ ok: true, connectionId }`，或 `{ ok: false, reason }`。
 * share 自己抛出的错（网络/后端 code=1）原样往外抛，由调用方就地显示。
 */
export async function shareProjectToLibrary({ projectId, connections, share }) {
  const picked = pickShareConnectionId(connections)
  if (!picked.ok) return picked
  await share(projectId, picked.connectionId)
  return { ok: true, connectionId: picked.connectionId }
}
