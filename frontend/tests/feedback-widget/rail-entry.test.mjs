// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 反馈入口从「全局可拖动浮窗」改成「左栏 rail 底部固定图标」（dev-board#755）。
//
// 维护者拍板：「整个界面浮球太多、看起来非常混乱」——画布只留 AI 审校一颗，
// 反馈收进左栏 rail，全局不再有浮球。这里做源码级断言：浮钮与拖动一行都不许回来，
// 入口与面板落点各自钉死。真渲染走查另见汇报里的截图。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../src')
const read = (p) => readFileSync(join(SRC, p), 'utf8')
// 注释里说得出「撤掉了什么」是好事，断言只看真代码
const stripComments = (s) => s
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '')

const WIDGET = read('components/FeedbackWidget.vue')
const MOUNT = read('utils/feedbackWidget.js')
const WORKBENCH = read('pages/project-overview/project-overview.vue')
const PROJECT_LIST = read('pages/project-list/project-list.vue')
const ICONS = read('config/icons.js')

test('FeedbackWidget 不再有浮钮：没有 .awdfb-launcher，没有拖动处理器', () => {
  assert.ok(!WIDGET.includes('awdfb-launcher'), '浮钮已撤，.awdfb-launcher 不许再出现')
  for (const token of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel']) {
    assert.ok(!WIDGET.includes(token), `拖动处理器残留：${token}`)
  }
  for (const token of ['onLauncherDown', 'onLauncherMove', 'onLauncherUp', 'detachLauncherDrag', 'clampPos', 'launcherSize']) {
    assert.ok(!WIDGET.includes(token), `拖动方法残留：${token}`)
  }
})

test('FeedbackWidget 不再持久化入口位置，也不再有让路机制', () => {
  assert.ok(!WIDGET.includes('awd_feedback_launcher_pos'), '入口位置的持久化键必须一并删掉')
  assert.ok(!WIDGET.includes('launcherPos'), 'launcherPos 残留')
  assert.ok(!stripComments(WIDGET).includes('keepClear'), '浮钮让路（keepClear）随浮钮一起撤')
  assert.ok(!existsSync(join(SRC, 'utils/keepClear.js')),
    'utils/keepClear.js 只为浮钮避开主操作区而存在，浮钮撤了它就是死代码')
})

test('反馈面板固定在窗口左下（挨着 rail），不再跟着入口象限跑', () => {
  assert.ok(!WIDGET.includes('panelStyle'), '面板不再按入口象限算落点，panelStyle 应删掉')
  const css = WIDGET.slice(WIDGET.indexOf('.awdfb-panel {'))
  const rule = css.slice(0, css.indexOf('}'))
  assert.match(rule, /left:/, '面板落点贴左：入口在左栏 rail 底部，面板从右下角冒出来对不上')
  assert.ok(!/\bright:/.test(rule), '面板不再贴右缘')
  assert.match(rule, /bottom:/, '面板贴底（让开工作台底部 26px 状态条）')
})

test('打开浮窗的唯一通道仍是 awd:open-feedback 事件', () => {
  // 浮窗是 body 级单例（feedbackWidget.js），页面组件够不到它的实例，只能走事件
  assert.match(WIDGET, /uni\.\$on\('awd:open-feedback'/, '浮窗必须继续订阅这个事件')
  assert.match(WIDGET, /uni\.\$off\('awd:open-feedback'/, '卸载时要摘掉监听')
  assert.match(MOUNT, /export function openFeedbackWidget/, '入口方的唯一出口')
  const fn = MOUNT.slice(MOUNT.indexOf('export function openFeedbackWidget'))
  assert.match(fn, /uni\.\$emit\('awd:open-feedback'\)/, 'openFeedbackWidget 必须发这个事件')
})

test('工作台 rail 底部有反馈按钮（spacer 之后，与暂存区/版本记录同一簇）', () => {
  const rail = WORKBENCH.slice(WORKBENCH.indexOf('<view class="left-rail">'))
  const railEnd = rail.indexOf('<!-- File Picker Dialog')
  assert.ok(railEnd > 0, '找不到 rail 段落的结尾')
  const body = rail.slice(0, railEnd)
  assert.match(body, /@tap="openFeedback"/, 'rail 里要有反馈按钮')
  assert.match(body, /GLYPHS\.feedback/, '反馈按钮用内联 SVG 图标（禁 emoji）')
  const spacerAt = body.indexOf('style="flex: 1"')
  assert.ok(spacerAt > 0 && body.indexOf('@tap="openFeedback"') > spacerAt, '反馈按钮在 spacer 之后（rail 底部）')
  assert.match(WORKBENCH, /openFeedback\s*\(\)\s*\{[^}]*openFeedbackWidget\(\)/,
    '工作台的 openFeedback 走 utils/feedbackWidget.js 的统一出口')
})

test('项目列表页（没有 rail）在页头保留反馈入口', () => {
  assert.match(PROJECT_LIST, /@tap="openFeedback"/, '启动落点页不能没有反馈入口')
  assert.match(PROJECT_LIST, /openFeedback\s*\(\)\s*\{[^}]*openFeedbackWidget\(\)/,
    '项目列表页的 openFeedback 走同一个出口')
})

test('反馈图标进 config/icons.js（唯一图标出处）', () => {
  assert.match(ICONS, /^\s*feedback:\s*\[/m, 'ICONS.feedback 缺失')
})
