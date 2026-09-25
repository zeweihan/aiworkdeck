// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 事项的模块级响应式缓存（dev-board#896，spec 2026-09-25-task-calendar-redesign 第二节）。
//
// 所有前端读写事项一律经这里：日程页议程、工作台日程面板、概览页 TaskSchedule、设置页
// 「我的待办」、rail 徽标、文件树到期徽标、本机提醒调度。写操作成功后就地更新两份缓存
// 并广播，四处清单与徽标不必各自重拉。
//
// 缓存形状：
//   taskStore.byProject[projectId] = { list, summary, loadedAt }
//     list 是该项目的全量事项（GET /api/projects/{id}/tasks 不带 from/to），
//     summary 用 taskUtils.summarizeTasks 本地算（全量在手，算得准），形状同后端 summary。
//   taskStore.global = { list, summary, loadedAt, ranges }
//     list 是跨项目事项按「已加载区间」拼起来的并集：loadGlobal({from,to}) 只替换落在
//     该区间内的那部分（日程页看九月、提醒调度拉前后 44 天，两者互不覆盖）；
//     不带 from/to 的全量加载直接整体替换，ranges 记为 [{from:null,to:null}]。
//     summary 是 GET /api/calendar/summary 的原样结果，写操作后若已加载过就重拉一次。
//
// 本文件依赖 @/services/api.js，node 测试里用源码抽取 + 注入依赖的办法跑
// （tests/calendar/task-store.test.mjs，同 tests/project-home/calendar-load-race 的口径）。
import { reactive } from 'vue'
import {
  getProjectTasks,
  getCalendarTasks,
  getCalendarSummary,
  createTask as apiCreateTask,
  updateTask as apiUpdateTask,
  deleteTask as apiDeleteTask,
} from '@/services/api.js'
import { compareDue, summarizeTasks, taskFileIds } from '@/components/calendar/taskUtils.js'

export const taskStore = reactive({
  byProject: {},
  global: { list: [], summary: null, loadedAt: 0, ranges: [] },
})

const listeners = new Set()
const inflight = new Map()
const projectSeq = {}
let summaryTimer = null

function tasksOf(res) {
  return (res && res.data && Array.isArray(res.data.tasks)) ? res.data.tasks : []
}

function taskOf(res) {
  return res && res.data && typeof res.data === 'object' ? res.data : null
}

function sameId(a, b) {
  return a != null && b != null && String(a) === String(b)
}

function emit(event) {
  for (const fn of Array.from(listeners)) {
    try {
      fn(event)
    } catch (e) {
      console.warn('[taskStore] 订阅回调出错', e)
    }
  }
}

function projectEntry(projectId) {
  const key = String(projectId)
  if (!taskStore.byProject[key]) {
    taskStore.byProject[key] = { list: [], summary: null, loadedAt: 0 }
  }
  return taskStore.byProject[key]
}

function refreshProjectSummary(projectId) {
  const entry = taskStore.byProject[String(projectId)]
  if (entry && entry.loadedAt) entry.summary = summarizeTasks(entry.list)
}

function inRange(task, from, to) {
  if (!from && !to) return true
  const d = task && task.dueDate
  if (!d) return false
  if (from && d < from) return false
  if (to && d > to) return false
  return true
}

function rangeCovered(from, to) {
  return taskStore.global.ranges.some((r) =>
    (r.from === null && r.to === null) ||
    (!!from && !!to && r.from && r.to && r.from <= from && r.to >= to))
}

function sortList(list) {
  return list.slice().sort(compareDue)
}

/** 同一请求在途时复用同一个 Promise（四个面板同时挂载只发一次）。 */
function once(key, factory) {
  if (inflight.has(key)) return inflight.get(key)
  const p = factory().finally(() => inflight.delete(key))
  inflight.set(key, p)
  return p
}

/**
 * 项目全量事项。已加载且不 force 时直接回缓存。
 * 返回该项目的事项数组（即缓存里那份，响应式）。
 */
export async function loadProjectTasks(projectId, { force } = {}) {
  if (projectId == null || projectId === '') return []
  const entry = projectEntry(projectId)
  if (entry.loadedAt && !force) return entry.list
  const key = String(projectId)
  return once('project:' + key, async () => {
    const seq = (projectSeq[key] || 0) + 1
    projectSeq[key] = seq
    const res = await getProjectTasks(projectId)
    // 乱序护栏：后发先至时丢弃旧响应
    if (projectSeq[key] !== seq) return projectEntry(projectId).list
    const e = projectEntry(projectId)
    e.list = sortList(tasksOf(res))
    e.loadedAt = Date.now()
    e.summary = summarizeTasks(e.list)
    emit({ kind: 'project-loaded', projectId })
    return e.list
  })
}

/**
 * 跨项目事项（GET /api/calendar）。from/to 为 YYYY-MM-DD（含端点），都不传 = 全部（含无日期）。
 * 区间已加载过且不 force 时回缓存里落在区间内的部分。
 * cache:false 只取数不写缓存（给不想改动缓存的一次性读取留口子，默认写）。
 */
export async function loadGlobal({ from, to, force, cache = true } = {}) {
  const f = from || null
  const t = to || null
  if (!force && cache && taskStore.global.loadedAt && rangeCovered(f, t)) {
    return taskStore.global.list.filter((x) => inRange(x, f, t))
  }
  return once('global:' + f + ':' + t + ':' + cache, async () => {
    // 不同区间的响应按区间合并、互不覆盖，所以这里不需要乱序护栏
    const res = await getCalendarTasks(f || undefined, t || undefined)
    const fetched = tasksOf(res)
    if (!cache) return sortList(fetched)
    const g = taskStore.global
    if (f === null && t === null) {
      g.list = sortList(fetched)
      g.ranges = [{ from: null, to: null }]
    } else {
      const kept = g.list.filter((x) => !inRange(x, f, t))
      g.list = sortList(kept.concat(fetched))
      if (!rangeCovered(f, t)) g.ranges = g.ranges.concat([{ from: f, to: t }])
    }
    g.loadedAt = Date.now()
    emit({ kind: 'global-loaded', from: f, to: t })
    return sortList(fetched)
  })
}

/** GET /api/calendar/summary，结果写进 taskStore.global.summary 并返回。 */
export async function loadSummary() {
  return once('summary', async () => {
    const res = await getCalendarSummary()
    const summary = (res && res.data) || null
    taskStore.global.summary = summary
    emit({ kind: 'summary-loaded' })
    return summary
  })
}

/** 写操作后：全局 summary 加载过的话 300ms 去抖重拉一次（跨项目口径只能后端算）。 */
function bumpSummary() {
  if (!taskStore.global.summary) return
  if (summaryTimer) clearTimeout(summaryTimer)
  summaryTimer = setTimeout(() => {
    summaryTimer = null
    loadSummary().catch((e) => console.warn('[taskStore] 刷新事项概览失败', e))
  }, 300)
}

/** 从缓存里找项目名（POST/PUT 的响应不带 projectName，只有 /api/calendar 带）。 */
function projectNameOf(projectId) {
  const hit = taskStore.global.list.find((x) => sameId(x.projectId, projectId) && x.projectName)
  return hit ? hit.projectName : undefined
}

function withProjectName(task, fallback) {
  if (!task || task.projectName) return task
  const name = (fallback && fallback.projectName) || projectNameOf(task.projectId)
  return name ? { ...task, projectName: name } : task
}

function upsert(list, task) {
  const i = list.findIndex((x) => sameId(x.id, task.id))
  const next = list.slice()
  if (i >= 0) next[i] = { ...next[i], ...task }
  else next.push(task)
  return sortList(next)
}

function removeFrom(list, id) {
  return list.filter((x) => !sameId(x.id, id))
}

function placeEverywhere(task, previous) {
  // 项目缓存：只在已加载的项目里就地放（没加载过的项目下次加载自然拿到）
  if (previous && !sameId(previous.projectId, task.projectId)) {
    const old = taskStore.byProject[String(previous.projectId)]
    if (old && old.loadedAt) {
      old.list = removeFrom(old.list, task.id)
      refreshProjectSummary(previous.projectId)
    }
  }
  const entry = taskStore.byProject[String(task.projectId)]
  if (entry && entry.loadedAt) {
    entry.list = upsert(entry.list, task)
    refreshProjectSummary(task.projectId)
  }
  const g = taskStore.global
  if (g.loadedAt) {
    const covered = g.ranges.some((r) => inRange(task, r.from, r.to))
    g.list = covered ? upsert(g.list, task) : removeFrom(g.list, task.id)
  }
}

function findCached(id) {
  const g = taskStore.global.list.find((x) => sameId(x.id, id))
  if (g) return g
  for (const entry of Object.values(taskStore.byProject)) {
    const hit = entry.list.find((x) => sameId(x.id, id))
    if (hit) return hit
  }
  return null
}

/**
 * 新建事项。body 同 POST /api/tasks；meta.projectName 可选（全局创建时弹窗知道项目名）。
 * 返回后端回的事项（补上 projectName）。
 */
export async function createTask(body, meta = {}) {
  const res = await apiCreateTask(body)
  let task = taskOf(res)
  if (!task || task.id == null) throw new Error('create task: empty response')
  task = withProjectName(task, meta)
  placeEverywhere(task, null)
  bumpSummary()
  emit({ kind: 'created', task })
  return task
}

/** 修改事项。patch 同 PUT /api/tasks/{id}。返回合并后的事项。 */
export async function updateTask(id, patch) {
  const previous = findCached(id)
  const res = await apiUpdateTask(id, patch)
  let task = taskOf(res)
  if (!task || task.id == null) task = { ...(previous || { id }), ...patch }
  task = withProjectName(task, previous)
  placeEverywhere(task, previous)
  bumpSummary()
  emit({ kind: 'updated', task, previous })
  return task
}

/** 删除事项，从所有缓存里摘掉。 */
export async function deleteTask(id) {
  const previous = findCached(id)
  await apiDeleteTask(id)
  for (const [pid, entry] of Object.entries(taskStore.byProject)) {
    if (entry.list.some((x) => sameId(x.id, id))) {
      entry.list = removeFrom(entry.list, id)
      refreshProjectSummary(pid)
    }
  }
  taskStore.global.list = removeFrom(taskStore.global.list, id)
  bumpSummary()
  emit({ kind: 'deleted', id, task: previous })
  return previous
}

/**
 * 关联了某文件的事项（旧列 fileId 与 files[] 任一命中），按到期排序，含已完成。
 * 只读项目缓存：调用方（文件树）先 loadProjectTasks(projectId)。
 */
export function tasksForFile(projectId, fileId) {
  const entry = taskStore.byProject[String(projectId)]
  if (!entry || fileId == null) return []
  const target = String(fileId)
  return entry.list.filter((t) => taskFileIds(t).includes(target))
}

/** 订阅变更（created/updated/deleted/project-loaded/global-loaded/summary-loaded）。返回退订函数。 */
export function subscribe(fn) {
  if (typeof fn !== 'function') return () => {}
  listeners.add(fn)
  return () => listeners.delete(fn)
}
