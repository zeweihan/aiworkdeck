// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// insightEntityTab.js — 「依据」实体详情标签页的开法（dev-board#541）。
//
// 外置成方法组（this 即 project-overview 页面实例，同 tabDragSplit.js 等 Phase 1-3
// 的先例）是为了能被 node --test 拿一个假 this 跑：这段里有两条真会打扰用户的判断
// ——**未分屏时强制开分屏并落到右侧**、**同一个实体不开第二个标签**——
// 埋在 6000 行的 .vue 里就没人守得住。单测在 tests/insight/insightEntityTab.test.mjs。

/** 标签类型。fileKind.js 的 NON_FILE_TAB_TYPES 里必须有它（不然标签会被按扩展名上色）。 */
export const INSIGHT_ENTITY_TAB_TYPE = 'insight-entity'

/** 单例标签 id：同一个实体在两侧任意一边开过，就不再开第二个。 */
export function insightEntityTabId(entity) {
  if (!entity || !entity.id) return ''
  return `${INSIGHT_ENTITY_TAB_TYPE}_${entity.kind || 'COMPANY'}_${entity.id}`
}

export const insightEntityTabMethods = {
  /**
   * 浮窗上的「在新标签页打开」：在**右侧分屏**开一个实体详情标签。
   *
   * 未分屏时先把分屏打开——这个动作的意义就是「正文与详情并排看」，
   * 开在左边会把用户正在读的那份文档顶掉。已经开过的标签只激活、不重建
   * （重建 = 详情再拉一次，还会把用户在那一页的滚动位置抹掉）。
   *
   * 标签直接 push 进列表（绕过 isFileTypeSupported），同 openMarketDetail 的形制。
   */
  openInsightEntityTab(payload) {
    const entity = (payload && payload.entity) || payload
    const tabId = insightEntityTabId(entity)
    if (!tabId) return
    this.closeInsightHoverCard()
    for (const pane of ['left', 'right']) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const existing = list.find((f) => f.id === tabId)
      if (existing) {
        this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
        this.focusedPane = pane
        this.$nextTick(() => this.triggerWorkbenchResize())
        return
      }
    }
    if (!this.splitMode) this.splitMode = true
    this.focusedPane = 'right'
    this.rightFiles.push({
      id: tabId,
      tabType: INSIGHT_ENTITY_TAB_TYPE,
      name: entity.name || tabId,
      entitySpec: { entity, detail: (payload && payload.detail) || null },
    })
    this.activeFileIdRight = tabId
    this.$nextTick(() => this.triggerWorkbenchResize())
  },
}
