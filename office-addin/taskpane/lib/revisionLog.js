// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 修订记录（dev-board#717）：别的窗格的 AI 改了本文档，痕迹留在**本文档自己的窗格**里。
 *
 * 维护者拍板：双向可写，但修订记录与痕迹必须在目标文档（B）的窗格里明示。Word 另有
 * 原生修订兜底；Excel / PPT 没有修订机制，这里是唯一的痕迹，也是一键撤销的依据
 * （条目里带改前值与改后值，见 crossDocWrite.js）。
 *
 * 模块级 store（import 即共享）：entries 是**当前文档**的条目，新在前。
 * 按文档持久化到 localStorage `awd_addin_revlog_{docKey}`，上限 200 条，超出丢最旧。
 * 未绑定文档时照样能记（只在内存里）——绑定由界面在挂载时按文档标识做。
 *
 * 存储写满的降级：Excel 改前值可能不小，200 条全带快照会撑爆 localStorage 配额。
 * 写失败时丢掉较早条目（最新 KEEP_SNAPSHOTS_ON_COMPACT 条之外）的改前/改后值再存一次，
 * 那些条目改标不可撤销并注明 snapshotDropped——宁可较早的撤不了，也不能整本记录从此
 * 存不进去、刷新后全没了。内存里同步改，界面不会显示「可撤销」而刷新后又不行。
 */
import { reactive, ref } from 'vue'

export const MAX_ENTRIES = 200
export const KEEP_SNAPSHOTS_ON_COMPACT = 20
const KEY_PREFIX = 'awd_addin_revlog_'

/** 当前文档的条目，新在前 */
export const entries = reactive([])
/** 未读条数（头部入口的角标） */
export const unread = ref(0)

let boundKey = ''
let boundStorage = null

function defaultStorage() {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null
  } catch (e) {
    return null
  }
}

function makeId() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return 'rev-' + crypto.randomUUID()
  } catch (e) { /* 老内核没有 randomUUID */ }
  return 'rev-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

function serialize() {
  return JSON.stringify({ v: 1, unread: unread.value, entries })
}

/** 丢掉较早条目的快照（就地改内存），返回是否真的丢了东西 */
function compactSnapshots() {
  let dropped = false
  for (let i = KEEP_SNAPSHOTS_ON_COMPACT; i < entries.length; i++) {
    const e = entries[i]
    if (e.before !== undefined || e.after !== undefined) {
      delete e.before
      delete e.after
      if (e.undoable) {
        e.undoable = false
        e.snapshotDropped = true
      }
      dropped = true
    }
  }
  return dropped
}

function persist() {
  if (!boundKey || !boundStorage) return
  try {
    boundStorage.setItem(KEY_PREFIX + boundKey, serialize())
  } catch (e) {
    if (!compactSnapshots()) return
    try {
      boundStorage.setItem(KEY_PREFIX + boundKey, serialize())
    } catch (e2) { /* 仍写不进去：本次会话内存里照样可用 */ }
  }
}

/**
 * 绑定到一份文档：换成该文档的条目与未读数。docKey 为空 = 解绑（只在内存里记）。
 */
export function bindDocument(docKey, storage = defaultStorage()) {
  boundKey = docKey ? String(docKey) : ''
  boundStorage = storage || null
  let loaded = []
  let loadedUnread = 0
  if (boundKey && boundStorage) {
    try {
      const raw = boundStorage.getItem(KEY_PREFIX + boundKey)
      if (raw) {
        const parsed = JSON.parse(raw)
        if (parsed && Array.isArray(parsed.entries)) loaded = parsed.entries.slice(0, MAX_ENTRIES)
        if (parsed && Number.isFinite(parsed.unread) && parsed.unread > 0) loadedUnread = parsed.unread
      }
    } catch (e) { /* 坏数据按空记录处理 */ }
  }
  entries.splice(0, entries.length, ...loaded)
  unread.value = loadedUnread
}

/**
 * 记一条。入参是 crossDocWrite.runCrossDocWrite 给出的条目草稿
 * （originDocName / originConversationId / command / summary / before / after / undoable 等），
 * 这里补上 id 与时间。返回存进去的那条。
 */
export function record(draft) {
  const entry = { ...(draft || {}), id: makeId(), time: Date.now() }
  entries.unshift(entry)
  if (entries.length > MAX_ENTRIES) entries.splice(MAX_ENTRIES)
  unread.value += 1
  persist()
  return entries[0]
}

export function markAllRead() {
  unread.value = 0
  persist()
}

/** 撤销成功后标记（持久化：刷新后不能再显示成可撤销） */
export function markUndone(id) {
  const e = entries.find((x) => x.id === id)
  if (!e) return
  e.undone = true
  persist()
}

export function remove(id) {
  const i = entries.findIndex((x) => x.id === id)
  if (i < 0) return
  entries.splice(i, 1)
  persist()
}

export function clear() {
  entries.splice(0, entries.length)
  unread.value = 0
  persist()
}

/* ==================== 面板开关、文档标识、时间显示（Task 13） ==================== */

/**
 * 修订记录面板是否打开（模块级，与 transfer.js 的 transferOpen 同一做法）：头部按钮与
 * composer 上方的横幅两个入口共用它，面板挂在 App.vue 顶层。
 */
export const revisionLogOpen = ref(false)

/** 打开面板即视为已读（角标清零并落盘，刷新后不能复活） */
export function openRevisionLog() {
  revisionLogOpen.value = true
  markAllRead()
}

/** 关闭时再清一次：面板开着期间进来的新条目，用户是看着它进来的 */
export function closeRevisionLog() {
  revisionLogOpen.value = false
  markAllRead()
}

/**
 * 修订记录的文档标识：有文件路径（Office 的文档 URL、WPS 的 FullName）就用路径；
 * 未保存的新文档没有路径，用「宿主:文档名」；连宿主都判不出（普通浏览器调试）返回空串，
 * bindDocument('') 即只记在内存里。
 */
export function docKeyOf({ path, host, docName } = {}) {
  const p = String(path || '').trim()
  if (p) return p
  const h = String(host || '').trim()
  return h ? `${h}:${String(docName || '').trim()}` : ''
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

/** 条目时间：当天只显示时分，同年其他日子带月日，跨年带年份 */
export function formatEntryTime(ts, now = Date.now()) {
  if (!Number.isFinite(ts)) return ''
  const d = new Date(ts)
  const n = new Date(now)
  const hm = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
  if (d.getFullYear() !== n.getFullYear()) {
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${hm}`
  }
  if (d.getMonth() !== n.getMonth() || d.getDate() !== n.getDate()) {
    return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${hm}`
  }
  return hm
}
