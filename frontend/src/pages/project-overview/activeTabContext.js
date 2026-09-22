// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「哪个标签能当活跃文档 / 能拖进 AI 上下文」的唯一出处（dev-board#779 K6 ②、K8 ①）。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/tab-visibility/active-tab-context.test.mjs。
//
// 病灶（审查发现 E-3）：中栏里混着一批虚拟标签——浏览器 `web_xxx`、AI 计划 artifact
// `artifact-<id>`、插件广场详情、设置、依据实体、提交历史、合并比对稿、版本对比……
// 它们和真文档一样会成为 activeFileLeft / activeFileRight，原来的 currentActiveTab
// 不做任何过滤，照样把它组装进 activeContext 交给后端。后端 read_document 第一行
// `Long.parseLong(fileId)` 抛 NumberFormatException，异常文案 `Error reading document:
// For input string: "artifact-12"` 被当成文档正文注进 <active_document>。
//
// 判据以 **id 形态** 为主：artifact 标签的 tabType 是 'markdown'、fileType 是 'md'，
// 光看类型它就是一份正常的 md，只有 id 认得出来。tabType 名单是第二道，为的是让
// 「这些标签不是文档」这条契约有个显式位置——将来有虚拟标签带上数字 id 时它兜得住。
import { NON_FILE_TAB_TYPES } from './fileKind.js'

// 真实项目文件的 id 是后端的 Long 主键（1 起），字符串化后必须整串都是数字：
// 后端 read_document / EditorBridge 一律 Long.parseLong，对不上就是上面那条异常路径。
const NUMERIC_ID = /^[1-9][0-9]*$/

/** 这个标签能不能当作「用户此刻正在看的文档」交给模型 */
export function isContextEligibleTab(tab) {
  if (!tab || typeof tab !== 'object') return false
  if (tab.tabType && NON_FILE_TAB_TYPES.indexOf(tab.tabType) !== -1) return false
  const id = tab.id == null ? '' : String(tab.id).trim()
  return NUMERIC_ID.test(id)
}

/**
 * currentActiveTab 的取值规则：聚焦窗格优先，不合格的标签当作「这一侧没开文档」。
 * 与原来的 `focusedPane === 'right' && activeFileRight ? activeFileRight
 * : (activeFileLeft || activeFileRight)` 逐条对应，只是多了一道合格性判定。
 */
export function pickActiveContextTab({ focusedPane, activeFileLeft, activeFileRight } = {}) {
  const left = isContextEligibleTab(activeFileLeft) ? activeFileLeft : null
  const right = isContextEligibleTab(activeFileRight) ? activeFileRight : null
  if (focusedPane === 'right' && right) return right
  return left || right || null
}
