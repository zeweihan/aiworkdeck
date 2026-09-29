// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// noProjectShell.js — 工作台无项目态（dev-board#1047，spec 2026-09-29-defer-login-welcome-tab-design §4）。
//
// 启动一律落工作台外壳（不带 ?id=）。此前不带 id 打开工作台是一个没设计过的半空壳：
// 十来个面板带着 projectId=null 空转，偶发 /api/projects/null/... 请求。现在无项目态有明确边界：
// rail 只留全局项，其余面板一律不挂载，右栏 AI 不渲染，暂存区不建。
//
// 零依赖纯函数（同 sidebarCollapse.js 的先例），node --test 直接导入。

/**
 * 无项目态 rail 上允许出现（也就允许挂载）的左栏面板。
 * - projects：左栏「项目」面板（项目列表），两态都有；
 * - calendar：日程面板，无项目时读全局事项；
 * - market：插件中心（广场免费项不需要项目，也不需要账户）；
 * - clipboard：剪贴板是账户级数据，不挂在项目上（只在用户把它停到左栏时出现在 rail 上）。
 * 文件树、搜索、版本、成员、语音、脱敏、诉讼可视化、概览、依据、收藏、插件开发全都要项目，不在名单里。
 */
export const NO_PROJECT_PANE_KEYS = ['projects', 'calendar', 'market', 'clipboard']

/** 无项目态左栏的默认面板 */
export const NO_PROJECT_DEFAULT_PANE = 'projects'

export function isPaneAllowedWithoutProject(key) {
  return NO_PROJECT_PANE_KEYS.includes(key)
}

/**
 * 工作台本机存储键。有项目按项目分，无项目落 `global_*`。
 * 此前无项目态写的是字面量 `project_null_*`——一把谁都不属于的钥匙。
 */
export function workbenchStorageKey(projectId, suffix) {
  const id = projectId == null ? '' : String(projectId).trim()
  return id && id !== 'null' && id !== 'undefined' ? `project_${id}_${suffix}` : `global_${suffix}`
}
