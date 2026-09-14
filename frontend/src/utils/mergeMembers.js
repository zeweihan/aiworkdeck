// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 本机成员表与团队案件库成员表的合并去重（dev-board#625）——**纯函数，不许 import**。
 *
 * 病灶：同一个官网账户在两张表里叫两个名字——本机是 `hanzewei`，案件库那边是
 * `awd_hanzewei`（桥接时加的前缀）。旧的去重只比 username 字符串，于是一个人
 * 在成员堆栈里显示成两个人（「2 人」，而案件库那边这份案卷只有 1 行成员）。
 *
 * 去重键按可靠度从高到低试三把：
 *  ① accountId（官网账户 id）相同 = 同一个人。两边都补了这个字段之后这是唯一权威判据；
 *  ② username 字面相同（自建多用户服务器的本机轨，两边本来就是同一张用户表）；
 *  ③ 云端 username === 'awd_' + 本机 username（桥接前缀，老数据里 accountId 可能为 null）。
 *
 * 合并后**保留本机那条**：它带得动本机 userId 与权限语义（canWriteProject /
 * canRemoveMember 拿它判「我是谁」）。role / joinedAt 以案件库为准覆盖——案卷放进库
 * 之后，加人改角色都发生在库那边，本机那条是加库之前的快照。
 *
 * 云端独有的人按老口径追加：id 加 `cloud-` 前缀避开 :key 撞号，**userId 一律抹成 null**
 * （案件库的 userId 与本机 user.id 是两个 id 空间，撞上会把别人的角色当成自己的）。
 */

const s = (v) => (v == null ? '' : String(v))

/** 云端条目 c 与本机条目 l 是不是同一个人。 */
export function sameMember(local, cloud) {
  if (!local || !cloud) return false
  const la = s(local.accountId)
  const ca = s(cloud.accountId)
  if (la && ca) return la === ca
  const lu = s(local.username)
  const cu = s(cloud.username)
  if (!lu || !cu) return false
  return cu === lu || cu === 'awd_' + lu
}

/**
 * @param {Array} local  本机 /projects/{id}/members
 * @param {Array} cloud  案件库 /cloud/projects/{id}/members 的 members
 * @returns {Array} 合并后的名单（本机在前，云端独有的追加在后）
 */
export function mergeMembers(local, cloud) {
  const locals = (Array.isArray(local) ? local : []).filter(Boolean)
  const clouds = (Array.isArray(cloud) ? cloud : []).filter(Boolean)
  if (!clouds.length) return locals.slice()

  const merged = locals.map((m) => ({ ...m }))
  const takenLocal = new Set()
  const extra = []

  for (const c of clouds) {
    let hit = -1
    for (let i = 0; i < merged.length; i++) {
      if (takenLocal.has(i)) continue
      if (sameMember(merged[i], c)) { hit = i; break }
    }
    if (hit >= 0) {
      takenLocal.add(hit)
      // 案件库是权威源：角色与加入时间以它为准；accountId 补上（本机那条可能是 null）
      if (c.role != null) merged[hit].role = c.role
      if (c.joinedAt != null) merged[hit].joinedAt = c.joinedAt
      if (merged[hit].accountId == null && c.accountId != null) merged[hit].accountId = c.accountId
      continue
    }
    extra.push({
      ...c,
      id: `cloud-${c.id != null ? c.id : s(c.username)}`,
      userId: null,
      fromCloud: true,
    })
  }

  return merged.concat(extra)
}
