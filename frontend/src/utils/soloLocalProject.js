// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「单人本机项目」判定：桌面端 local-mode 下只有本机用户一个人的项目，
 * 「负责人：本机用户」与皇冠徽标没有信息量，工作台顶栏与项目列表都不显示。
 *
 * 纯函数、不许 import（跑得了 node --test）。
 *
 * · localMode 必须是确定的 true：还没读到（null）或自建服务器（false）一律按多人处理，
 *   宁可多显示一行负责人，也不在服务器形态下把真实负责人藏掉；
 * · 人数 = managerId ∪ 成员里的 userId 去重。project_member 里 owner 可能另有一行，
 *   也可能没有，所以 managerId 要并进来算；
 * · 案件库并进来的云端成员 userId 被抹成 null（见 utils/mergeMembers.js），
 *   它们是真实的另一个人，按 id / username 单独计数，不能因为没有 userId 就漏算。
 */
export function isSoloLocalProject({ localMode, managerId, members } = {}) {
  if (localMode !== true) return false
  const people = new Set()
  if (managerId != null) people.add('u:' + managerId)
  for (const m of members || []) {
    if (!m) continue
    if (m.userId != null) people.add('u:' + m.userId)
    else people.add('c:' + (m.id != null ? m.id : m.username))
  }
  return people.size <= 1
}
