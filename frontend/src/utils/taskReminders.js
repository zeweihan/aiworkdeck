// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 事项的本机提醒调度（dev-board#901，spec 2026-09-25-task-calendar-redesign 第二节）。
//
// 服务端只存「提前多久」（remindBefore），这里在前端算提醒时刻并弹系统通知
// （HTML5 Notification，Electron 直通到系统通知中心）。不新增任何出站请求
// （legal/PRIVACY.md 红线）：数据全部来自既有的 GET /api/calendar。
//
// 用法：
//   import { startTaskReminders, todayDigest } from '@/utils/taskReminders.js'
//   startTaskReminders()   // 工作台与项目列表页挂载时各调一次，幂等（App 生命周期内单例）
//   await todayDigest()    // 项目列表页启动时调；返回 { overdue, today }，有事项时每天 toast 一次
//
// 调度：启动即跑一轮，此后每 5 分钟一轮；taskStore 有写操作/重新加载时 1.5 秒去抖补一轮。
// 每轮拉「今天前 30 天 ~ 今天后 14 天」的事项，按 taskUtils.dueReminders 判定该弹的。
// 已通知表存 localStorage `awd_task_notified` = { [uid]: remindAtISO }：同一次提醒只弹一次，
// 事项改了时间（remindAt 变了）会重新提醒。超过 24 小时的旧提醒不补弹、只记为已处理
// （刚升级/长期离线回来不会一次涌出几十条）；逾期事项由 todayDigest 的摘要兜底。
// 通知不可用（无 Notification、非安全上下文、用户拒绝）时降级为应用内 toast（AwdToastHost）。
import { t } from '@/i18n'
import { loadGlobal, loadSummary, subscribe } from '@/utils/taskStore.js'
import { typeMeta, dueReminders, localDateKey, addDaysKey, timeOf, formatMonthDay } from '@/components/calendar/taskUtils.js'

const NOTIFIED_KEY = 'awd_task_notified'
const DIGEST_KEY = 'awd_task_digest_date'
const TICK_MS = 5 * 60 * 1000
const CHANGE_DEBOUNCE_MS = 1500
/** 已通知表里超过这个天数的条目清掉，免得 localStorage 无限长 */
const NOTIFIED_TTL_DAYS = 45

let started = false
let timer = null
let changeTimer = null
let running = false
let permissionAsked = false

function readJson(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch (e) {
    return fallback
  }
}

function writeJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch (e) { /* 隐私模式/配额满：最多重复提醒一次，不影响功能 */ }
}

function pruneNotified(map, now) {
  const cutoff = now.getTime() - NOTIFIED_TTL_DAYS * 86400000
  const out = {}
  for (const [k, iso] of Object.entries(map || {})) {
    const ts = Date.parse(iso)
    if (!Number.isNaN(ts) && ts >= cutoff) out[k] = iso
  }
  return out
}

function notificationUsable() {
  if (typeof window === 'undefined' || typeof window.Notification === 'undefined') return false
  if (window.isSecureContext === false) return false
  return window.Notification.permission === 'granted'
}

async function ensurePermission() {
  if (permissionAsked) return
  permissionAsked = true
  try {
    const N = typeof window !== 'undefined' ? window.Notification : undefined
    if (!N || window.isSecureContext === false) return
    if (N.permission === 'default' && typeof N.requestPermission === 'function') {
      await N.requestPermission()
    }
  } catch (e) {
    console.warn('[taskReminders] 申请通知权限失败，改用应用内提示', e)
  }
}

function whenText(task) {
  const date = formatMonthDay(task.dueDate, t)
  const time = timeOf(task)
  return time ? t('calendar.notifyWhenTime', { date, time }) : t('calendar.notifyWhenAllDay', { date })
}

function showReminder(task) {
  const type = typeMeta(task.type, t).label
  const title = t('calendar.notifyTitle', { type, title: task.title || '' })
  const when = whenText(task)
  const body = task.projectName ? t('calendar.notifyBody', { project: task.projectName, when }) : when
  if (notificationUsable()) {
    try {
      const n = new window.Notification(title, { body, tag: String(task.uid || task.id) })
      n.onclick = () => {
        try { window.focus() } catch (e) { /* ignore */ }
        n.close()
        uni.navigateTo({ url: '/pages/calendar/calendar?focus=' + task.id })
      }
      return
    } catch (e) {
      console.warn('[taskReminders] 系统通知失败，改用应用内提示', e)
    }
  }
  uni.showToast({
    title: t('calendar.notifyToast', { type, title: task.title || '', when }),
    icon: 'none',
    duration: 6000,
  })
}

async function tick() {
  if (running) return
  running = true
  try {
    const now = new Date()
    const today = localDateKey(now)
    const tasks = await loadGlobal({ from: addDaysKey(today, -30), to: addDaysKey(today, 14), force: true })
    const notified = pruneNotified(readJson(NOTIFIED_KEY, {}), now)
    const { due, skipped } = dueReminders(tasks, notified, now)
    for (const entry of skipped) notified[entry.key] = entry.at
    for (const entry of due) {
      showReminder(entry.task)
      notified[entry.key] = entry.at
    }
    writeJson(NOTIFIED_KEY, notified)
  } catch (e) {
    console.warn('[taskReminders] 提醒检查失败', e)
  } finally {
    running = false
  }
}

function scheduleSoon(event) {
  // 自己这轮 loadGlobal 触发的 global-loaded 不再回头排一轮
  if (event && (event.kind === 'global-loaded' || event.kind === 'summary-loaded')) return
  if (changeTimer) clearTimeout(changeTimer)
  changeTimer = setTimeout(() => {
    changeTimer = null
    tick()
  }, CHANGE_DEBOUNCE_MS)
}

/** 启动提醒调度。幂等：重复调用什么都不做。 */
export function startTaskReminders() {
  if (started || typeof window === 'undefined') return
  started = true
  ensurePermission()
  tick()
  timer = setInterval(tick, TICK_MS)
  subscribe(scheduleSoon)
}

/**
 * 当日摘要：返回 { overdue, today }（取自 GET /api/calendar/summary）。
 * 有事项且今天还没提示过时弹一次 toast（localStorage 记日期）；options.toast=false 只取数。
 */
export async function todayDigest({ toast = true } = {}) {
  let summary = null
  try {
    summary = await loadSummary()
  } catch (e) {
    console.warn('[taskReminders] 读取事项概览失败', e)
  }
  const overdue = (summary && Number(summary.overdue)) || 0
  const today = (summary && Number(summary.today)) || 0
  if (toast && (overdue > 0 || today > 0)) {
    const dateKey = localDateKey()
    let shown = null
    try { shown = window.localStorage.getItem(DIGEST_KEY) } catch (e) { /* ignore */ }
    if (shown !== dateKey) {
      let title
      if (today > 0 && overdue > 0) title = t('calendar.digestTodayAndOverdue', { count: today, overdue })
      else if (today > 0) title = t('calendar.digestTodayOnly', { count: today })
      else title = t('calendar.digestOverdueOnly', { overdue })
      uni.showToast({ title, icon: 'none', duration: 5000 })
      try { window.localStorage.setItem(DIGEST_KEY, dateKey) } catch (e) { /* ignore */ }
    }
  }
  return { overdue, today }
}
