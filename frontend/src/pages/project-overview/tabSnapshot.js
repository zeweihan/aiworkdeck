// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// tabSnapshot.js — 工作台标签持久化（dev-board#1049，spec 2026-09-29-defer-login-welcome-tab-design §8）。
//
// 重启 / 切项目回来之后，中栏的标签条（左右两窗格）、各自的激活标签与分屏状态照原样回来，
// 形同 VS Code 的「恢复上次打开的编辑器」。
//
// 零依赖（不 import Vue / uni / '@/' 别名），node --test 直接导入（tests/project-home/tab-snapshot.test.mjs）。
// 方法组里的 this 是 project-overview 页面实例；存储走 this.$tabStorage（测试注入）或全局 uni。
//
// 契约：
//   1. 两份快照：无项目态 `global_tabs`，有项目 `project_${id}_tabs`（键由 noProjectShell.workbenchStorageKey 出），
//      存 uni.storage；形状 { v:1, left:[], right:[], activeLeft, activeRight, splitMode, focusedPane }。
//   2. 每个标签只存白名单字段（下面 SERIALIZERS），深链类一次性字段（pendingLocator、日程的 focus/group、
//      提交历史的 focus/focusSha/token）不存——重启时再弹一次定位 / 编辑框是打扰。
//   3. 不存的标签：合并比对稿（裁决只在引擎实例里，关了就没了；后端待决记录会让版本面板再给入口）、
//      AI 产物 markdown（内容只在内存）、以及任何认不出来的 tabType（向前兼容：新类型
//      没登记白名单就不恢复，不会带着缺字段的对象进模板）。
//   4. 恢复：文件类标签（id 为数字）逐个核对文件仍在（fileExists），不在的静默丢弃；对比标签两份都要在。
//      无项目态只恢复全局标签（欢迎 / 日程 / 设置 / 插件详情 / 网页）。单例标签（SINGLETON_TAB_TYPES）
//      跨两窗格按 id 去重（先左后右）；同一窗格里任何 id 重复都只留第一个。未分屏时右窗格的标签并进左窗格。
//   5. 恢复只恢复标签条：引擎文档（LOWA）只有激活的那一个会挂载启动（librePool 的保活池只收
//      激活 + LRU，LRU 初始为空），其余标签点到时才加载，不会一次拉起多份文档撞保活上限。
//   6. 写入：标签增删改 / 激活切换 / 分屏变化 → 节流 300ms；leaveWorkbench 与页面卸载前同步写一次。
//      恢复完成之前不写（否则恢复窗口里的空标签条会先把快照覆盖掉）。

import { workbenchStorageKey } from './noProjectShell.js'

export const TAB_SNAPSHOT_VERSION = 1
export const TAB_SNAPSHOT_SUFFIX = 'tabs'
export const TAB_SNAPSHOT_THROTTLE_MS = 300

/** 全局单例标签：一个工作台里至多一个，恢复时跨两窗格按 id 去重 */
export const SINGLETON_TAB_TYPES = ['welcome', 'calendar', 'admin-settings', 'market-detail', 'commit-history', 'insight-entity', 'plugin']

/** 只在有项目时才有意义的标签（文件标签另判） */
const PROJECT_ONLY_KINDS = ['file', 'dd-request', 'diff', 'version-compare', 'version-text-diff', 'commit-history', 'insight-entity', 'plugin']

const NUMERIC_ID = /^[1-9][0-9]*$/

function isNumericId(id) {
  return id !== null && id !== undefined && NUMERIC_ID.test(String(id).trim())
}

function str(v) {
  return v === null || v === undefined ? '' : String(v)
}

/** 纯数据深拷贝：只留 JSON 能表达的部分（spec 对象里偶尔混进函数 / 循环引用时宁可丢掉整条） */
function plain(v) {
  if (v === null || v === undefined) return null
  try {
    return JSON.parse(JSON.stringify(v))
  } catch (e) {
    return undefined
  }
}

/** 标签归类：tabType 优先；没有 tabType 的只认两种——真文件（数字 id）与尽调清单 */
export function tabKind(tab) {
  if (!tab || typeof tab !== 'object' || tab.id === null || tab.id === undefined || tab.id === '') return null
  if (tab.tabType) return String(tab.tabType)
  if (tab.type === 'dd-request') return 'dd-request'
  if (tab.fileType === 'plugin') return null
  if (tab.isFolder) return null
  return isNumericId(tab.id) ? 'file' : null
}

const SERIALIZERS = {
  file: (t) => ({
    id: t.id,
    name: str(t.name),
    fileType: str(t.fileType),
    wpsFileId: t.wpsFileId === undefined ? null : t.wpsFileId,
    filePath: t.filePath === undefined ? null : t.filePath,
  }),
  'dd-request': (t) => (t.requestId === null || t.requestId === undefined ? null : {
    id: t.id, requestId: t.requestId, name: str(t.name), type: 'dd-request', fileType: 'dd', isFolder: false,
  }),
  web: (t) => (typeof t.url === 'string' && t.url ? { id: t.id, tabType: 'web', name: str(t.name), url: t.url } : null),
  plugin: (t) => (typeof t.pluginId === 'string' && t.pluginId ? {
    id: `plugin-${t.pluginId}`, tabType: 'plugin', pluginId: t.pluginId, name: str(t.name),
  } : null),
  welcome: (t) => ({ id: t.id, tabType: 'welcome', name: str(t.name) }),
  calendar: (t) => ({ id: t.id, tabType: 'calendar', name: str(t.name) }),
  'admin-settings': (t) => ({
    id: t.id, tabType: 'admin-settings', name: str(t.name), adminNav: str(t.adminNav), adminService: str(t.adminService),
  }),
  'market-detail': (t) => {
    const spec = plain(t.marketSpec)
    return spec ? { id: t.id, tabType: 'market-detail', name: str(t.name), marketSpec: spec } : null
  },
  'insight-entity': (t) => {
    const spec = plain(t.entitySpec)
    return spec ? { id: t.id, tabType: 'insight-entity', name: str(t.name), entitySpec: spec } : null
  },
  'commit-history': (t) => ({ id: t.id, tabType: 'commit-history', name: str(t.name) }),
  diff: (t) => {
    const s = plain(t.diffSource)
    const g = plain(t.diffTarget)
    return s && g && s.id != null && g.id != null
      ? { id: t.id, tabType: 'diff', fileType: 'diff', name: str(t.name), diffSource: s, diffTarget: g }
      : null
  },
  'version-compare': (t) => {
    const spec = plain(t.compareSpec)
    return spec ? { id: t.id, tabType: 'version-compare', fileType: 'version-compare', name: str(t.name), compareSpec: spec } : null
  },
  'version-text-diff': (t) => {
    const spec = plain(t.versionSpec)
    return spec ? { id: t.id, tabType: 'version-text-diff', fileType: 'version-text-diff', name: str(t.name), versionSpec: spec } : null
  },
  'tmeet-transcript': (t) => {
    const spec = plain(t.meetingSpec)
    return spec ? { id: t.id, tabType: 'tmeet-transcript', name: str(t.name), meetingSpec: spec } : null
  },
}

/** 恢复时补回一次性字段的默认值（模板与组件读得到，但不带上次的深链） */
const HYDRATORS = {
  file: (t) => ({ ...t, pendingLocator: null }),
  calendar: (t) => ({ ...t, calendarFocus: '', calendarGroup: '' }),
  'commit-history': (t) => ({ ...t, historyFocus: '', historyFocusSha: '', historyFocusToken: 0 }),
}

/** 单个标签 → 快照项；不可恢复的返回 null */
export function serializeTab(tab) {
  const kind = tabKind(tab)
  const fn = kind ? SERIALIZERS[kind] : null
  if (!fn) return null
  const out = fn(tab)
  return out || null
}

function serializeList(list) {
  const out = []
  for (const t of Array.isArray(list) ? list : []) {
    const s = serializeTab(t)
    if (s) out.push(s)
  }
  return out
}

/**
 * 当前标签状态 → 快照对象（JSON 安全，uni.setStorageSync 直接存）。
 * @param splitState {{splitMode:boolean, focusedPane:'left'|'right'}}
 */
export function serialize(leftFiles, rightFiles, activeLeft, activeRight, splitState = {}) {
  const left = serializeList(leftFiles)
  const right = serializeList(rightFiles)
  const has = (list, id) => id !== null && id !== undefined && list.some((t) => t.id === id)
  const splitMode = !!(splitState && splitState.splitMode)
  return {
    v: TAB_SNAPSHOT_VERSION,
    left,
    right,
    activeLeft: has(left, activeLeft) ? activeLeft : null,
    activeRight: has(right, activeRight) ? activeRight : null,
    splitMode,
    focusedPane: splitMode && splitState.focusedPane === 'right' ? 'right' : 'left',
  }
}

/** 快照里有没有需要核对「文件还在不在」的标签（没有就不必发那一个 GET） */
export function snapshotNeedsFileCheck(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return false
  const all = [].concat(Array.isArray(snapshot.left) ? snapshot.left : [], Array.isArray(snapshot.right) ? snapshot.right : [])
  return all.some((t) => {
    const k = tabKind(t)
    return k === 'file' || k === 'diff'
  })
}

const FRESH_FILE_FIELDS = ['name', 'fileType', 'wpsFileId', 'filePath']

function keepTab(t, ctx) {
  const kind = tabKind(t)
  if (!kind || !SERIALIZERS[kind]) return null
  // 快照里的对象也过一遍白名单：手改过的 storage、旧版本多出来的字段一律不带进模板
  const base = SERIALIZERS[kind](t)
  if (!base) return null
  if (!ctx.hasProject && PROJECT_ONLY_KINDS.includes(kind)) return null
  if (kind === 'file' && ctx.fileExists) {
    if (!ctx.fileExists(base.id)) return null
    const fresh = ctx.resolveFile ? ctx.resolveFile(base.id) : null
    if (fresh && typeof fresh === 'object') {
      for (const f of FRESH_FILE_FIELDS) {
        if (fresh[f] !== undefined && fresh[f] !== null) base[f] = fresh[f]
      }
      // 保留快照里的 id 形态（数字 / 字符串），不重建编辑器实例（同 openFile 的口径）
    }
  }
  if (kind === 'diff' && ctx.fileExists) {
    if (!ctx.fileExists(base.diffSource.id) || !ctx.fileExists(base.diffTarget.id)) return null
  }
  const hydrate = HYDRATORS[kind]
  return hydrate ? hydrate(base) : base
}

/**
 * 快照 → 过滤后的标签集合；快照缺失 / 版本不认识时返回 null。
 * @param opts.hasProject  无项目态只恢复全局标签（默认 true）
 * @param opts.fileExists  (id) => boolean；不传 = 无法核对（列表拉失败），文件标签原样保留
 * @param opts.resolveFile (id) => 最新文件对象；有则用它刷新 name / fileType 等（改名之后不陈旧）
 */
export function restore(snapshot, opts = {}) {
  if (!snapshot || typeof snapshot !== 'object' || snapshot.v !== TAB_SNAPSHOT_VERSION) return null
  const ctx = {
    hasProject: opts.hasProject !== false,
    fileExists: typeof opts.fileExists === 'function' ? opts.fileExists : null,
    resolveFile: typeof opts.resolveFile === 'function' ? opts.resolveFile : null,
  }
  const seenSingleton = new Set()
  const take = (list) => {
    const out = []
    const seen = new Set()
    for (const raw of Array.isArray(list) ? list : []) {
      const t = keepTab(raw, ctx)
      if (!t) continue
      const key = String(t.id)
      if (seen.has(key)) continue
      if (t.tabType && SINGLETON_TAB_TYPES.includes(t.tabType)) {
        if (seenSingleton.has(key)) continue
        seenSingleton.add(key)
      }
      seen.add(key)
      out.push(t)
    }
    return out
  }
  let left = take(snapshot.left)
  let right = take(snapshot.right)
  let splitMode = !!snapshot.splitMode
  let activeLeft = snapshot.activeLeft
  let activeRight = snapshot.activeRight
  if (!splitMode && right.length) {
    // 右窗格只在分屏时存在（关分屏会把右侧并进左侧，C9-02）；快照里对不上就照那条规则并过去
    const ids = new Set(left.map((t) => String(t.id)))
    left = left.concat(right.filter((t) => !ids.has(String(t.id))))
    if (!left.some((t) => t.id === activeLeft)) activeLeft = activeRight
    right = []
    activeRight = null
  }
  const pickActive = (list, id) => (list.some((t) => t.id === id) ? id : (list.length ? list[0].id : null))
  return {
    leftFiles: left,
    rightFiles: right,
    activeLeft: pickActive(left, activeLeft),
    activeRight: pickActive(right, activeRight),
    splitMode,
    focusedPane: splitMode && snapshot.focusedPane === 'right' ? 'right' : 'left',
  }
}

/**
 * 把恢复结果并进「恢复期间已经开了的标签」（深链 openFileId、conversationId 一类会在
 * 恢复的那个 GET 往返里先开标签）。恢复的在前、已开的在后；已开且被激活的保持激活。
 */
export function mergeRestored(restored, current) {
  const cur = current || {}
  const merged = {}
  const restoredIds = new Set([].concat(restored.leftFiles, restored.rightFiles).map((t) => String(t.id)))
  for (const pane of ['left', 'right']) {
    const rList = pane === 'left' ? restored.leftFiles : restored.rightFiles
    const cList = (pane === 'left' ? cur.leftFiles : cur.rightFiles) || []
    merged[pane === 'left' ? 'leftFiles' : 'rightFiles'] = rList.concat(cList.filter((t) => !restoredIds.has(String(t.id))))
    const cActive = pane === 'left' ? cur.activeLeft : cur.activeRight
    const rActive = pane === 'left' ? restored.activeLeft : restored.activeRight
    const list = merged[pane === 'left' ? 'leftFiles' : 'rightFiles']
    const keepCurrent = cActive !== null && cActive !== undefined && cList.some((t) => t.id === cActive)
    merged[pane === 'left' ? 'activeLeft' : 'activeRight'] = keepCurrent ? cActive : (list.some((t) => t.id === rActive) ? rActive : null)
  }
  merged.splitMode = !!(restored.splitMode || cur.splitMode || merged.rightFiles.length)
  const pane = cur.focusedPaneTouched ? cur.focusedPane : restored.focusedPane
  merged.focusedPane = merged.splitMode && pane === 'right' ? 'right' : 'left'
  return merged
}

function storageOf(vm) {
  if (vm && vm.$tabStorage) return vm.$tabStorage
  return typeof uni !== 'undefined' ? uni : null // eslint-disable-line no-undef
}

// 名字随语言走的单例标签：恢复时按当前语言重取，不用快照里存的旧语言文案
const LOCALIZED_NAME_KEYS = {
  welcome: 'welcome.tabName',
  calendar: 'calendar.tabName',
  'admin-settings': 'workbench.settingsTabName',
  'commit-history': 'version.historyTabName',
}

export const tabSnapshotMethods = {
  tabSnapshotKey() {
    return workbenchStorageKey(this.projectId, TAB_SNAPSHOT_SUFFIX)
  },

  currentTabSnapshot() {
    return serialize(this.leftFiles, this.rightFiles, this.activeFileIdLeft, this.activeFileIdRight, {
      splitMode: this.splitMode,
      focusedPane: this.focusedPane,
    })
  },

  writeTabSnapshot() {
    const storage = storageOf(this)
    if (!storage) return
    try {
      storage.setStorageSync(this.tabSnapshotKey(), this.currentTabSnapshot())
    } catch (e) {
      // 存储不可用：本次会话照常，只是下次不恢复
    }
  },

  /** 节流写：300ms 内的多次变化合成一次（尾写，取最后状态） */
  scheduleTabSnapshotSave() {
    if (!this._tabSnapshotReady) return
    if (this._tabSnapshotTimer) return
    this._tabSnapshotTimer = setTimeout(() => {
      this._tabSnapshotTimer = null
      this.writeTabSnapshot()
    }, TAB_SNAPSHOT_THROTTLE_MS)
  },

  /** 同步写一次、不节流：leaveWorkbench 前与页面卸载时 */
  flushTabSnapshot() {
    if (this._tabSnapshotTimer) {
      clearTimeout(this._tabSnapshotTimer)
      this._tabSnapshotTimer = null
    }
    if (!this._tabSnapshotReady) return
    this.writeTabSnapshot()
  },

  /**
   * onLoad 调一次。返回是否恢复出了至少一个标签。
   * 文件核对用宿主的 fetchTabSnapshotFileIndex()（一次 GET 整棵树 → Map<String(id), file>），
   * 拉失败就不核对（文件标签原样保留，打开时编辑器自己会报「文件不存在」）。
   */
  async restoreTabSnapshot() {
    const storage = storageOf(this)
    let snap = null
    try {
      snap = storage ? storage.getStorageSync(this.tabSnapshotKey()) : null
    } catch (e) {
      snap = null
    }
    let restored = null
    if (snap && typeof snap === 'object') {
      let index = null
      if (this.hasProject && snapshotNeedsFileCheck(snap) && typeof this.fetchTabSnapshotFileIndex === 'function') {
        try {
          index = await this.fetchTabSnapshotFileIndex()
        } catch (e) {
          index = null
        }
      }
      restored = restore(snap, {
        hasProject: !!this.hasProject,
        fileExists: index ? (id) => index.has(String(id)) : null,
        resolveFile: index ? (id) => index.get(String(id)) : null,
      })
    }
    const any = !!(restored && (restored.leftFiles.length || restored.rightFiles.length))
    if (any) {
      for (const t of [].concat(restored.leftFiles, restored.rightFiles)) {
        const key = t.tabType && LOCALIZED_NAME_KEYS[t.tabType]
        if (key && typeof this.$t === 'function') t.name = this.$t(key)
      }
      const merged = mergeRestored(restored, {
        leftFiles: this.leftFiles,
        rightFiles: this.rightFiles,
        activeLeft: this.activeFileIdLeft,
        activeRight: this.activeFileIdRight,
        splitMode: this.splitMode,
        focusedPane: this.focusedPane,
        focusedPaneTouched: !!(this.leftFiles.length || this.rightFiles.length),
      })
      this.leftFiles.splice(0, this.leftFiles.length, ...merged.leftFiles)
      this.rightFiles.splice(0, this.rightFiles.length, ...merged.rightFiles)
      this.splitMode = merged.splitMode
      this.focusedPane = merged.focusedPane
      // 快照优先于 activeTabsByMode 的旧记忆（spec §8）
      this.activeFileIdLeft = merged.activeLeft
      this.activeFileIdRight = merged.activeRight
    }
    this.reconcilePluginTabs?.()
    this._tabSnapshotReady = true
    this.scheduleTabSnapshotSave()
    return any
  },
}
