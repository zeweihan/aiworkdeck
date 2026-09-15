// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「展示名与头像该写去哪儿」的取数与已读记录（判定本身在 utils/identityProfile.js，
 * 那边不许 import，才跑得了 node --test）。
 *
 * 侧栏用户卡（AdminPane）与个人设置（PersonalSettingsPanel）两处共用：两处的头像
 * 点击必须落到同一条路上，分开各写一份迟早会一边写官网一边写本机。
 */
import { getLocalIdentityStatus, getAccountStatus, getAccountProfile } from '@/services/api.js'
import {
  PROFILE_SOURCE, resolveProfileSource,
  NAME_NUDGE_STORAGE_KEY, withNudgeDismissed,
} from '@/utils/identityProfile.js'

export { PROFILE_SOURCE }

// local-mode 是一台机器的装机形态，一次进程内不会变（同 InviteMemberDialog 的缓存理由）。
let localModeCache = null
async function readLocalMode() {
  if (localModeCache !== null) return localModeCache
  try {
    // 这个端点回裸 JSON（没有 code/data 包装），见 api.js 的注释
    const identity = await getLocalIdentityStatus()
    localModeCache = !!(identity && identity.localMode)
  } catch (e) {
    // 读不到按「不是 local-mode」处理：那条路最差只是昵称不可改、头像传去本机，
    // 反过来（误判成 local-mode）会给自建服务器用户一个必然 404 的输入框。
    localModeCache = false
  }
  return localModeCache
}

/**
 * 拉一次「我是谁、名字与头像归谁管」。
 *
 * 回 { source, profile }：
 *  · source === ACCOUNT 时 profile 是官网那份 { accountId, displayName, avatarUrl, displayNameIsDefault }；
 *  · source === LOCAL 时 profile 为 null（本机 /api/auth/me 那份由调用方自己拿）。
 * 全程不抛：官网不可达、老后端没有这个端点，一律降级成 LOCAL，界面回到只读形态。
 */
export async function loadIdentityProfile() {
  const localMode = await readLocalMode()
  let connected = false
  try {
    const status = await getAccountStatus()
    connected = !!(status && status.connected)
  } catch (e) {
    connected = false
  }
  if (resolveProfileSource({ localMode, connected }) !== PROFILE_SOURCE.ACCOUNT) {
    return { source: PROFILE_SOURCE.LOCAL, profile: null }
  }
  try {
    const profile = await getAccountProfile()
    if (!profile) return { source: PROFILE_SOURCE.LOCAL, profile: null }
    return { source: PROFILE_SOURCE.ACCOUNT, profile }
  } catch (e) {
    // 后端还没上这个端点（桌面端与后端不同步发布时的常态）：按自建服务器那套画，
    // 用户看到的是今天的只读形态，不是一个报错。
    return { source: PROFILE_SOURCE.LOCAL, profile: null }
  }
}

/** 已经打发过「填写你的姓名」弹窗的 accountId 列表。读不到就当空。 */
export function readNudgeDismissed() {
  try {
    const raw = uni.getStorageSync(NAME_NUDGE_STORAGE_KEY)
    return Array.isArray(raw) ? raw : []
  } catch (e) {
    return []
  }
}

/** 记下这个账户已打发过。写不进去不报错——最坏是下次再弹一次，不该为此打断用户。 */
export function markNudgeDismissed(accountId) {
  try {
    uni.setStorageSync(NAME_NUDGE_STORAGE_KEY, withNudgeDismissed(readNudgeDismissed(), accountId))
  } catch (e) {
    /* 存不下就算了 */
  }
}
