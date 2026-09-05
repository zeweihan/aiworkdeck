// 面板拖拽上限必须按面板所在的容器算，不能按整窗宽算（dev-board#459）。
//
// 病灶：applyResizeFrame 用 `Math.floor(window.innerWidth * 0.75)` 当左右两侧面板的
// 拖拽上限，可是 .side-panel-ai 的父容器 .workbench-main 只拿到「整窗 − 50px rail −
// 左栏宽」。.side-panel{flex-shrink:0} 让面板不肯让宽，.workbench{overflow:hidden} 又
// 把溢出裁掉——于是编辑区先塌成 0，随后 AI 面板右半边被裁在窗口右缘之外（输入框/发送
// 键看不见也点不着），看上去就像「窗口被撑出屏幕」。把窗口放大（窗口→缩放）会让
// .workbench 变宽、裁切消失，正是复测者找到的恢复手法。
//
// 左栏那支是同一个缺陷的镜像：拖宽左栏同样能把 AI 面板顶出容器。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  EDITOR_MIN_WIDTH,
  rightPanelMaxWidth,
  leftPanelMaxWidth
} from '../../src/pages/project-overview/panelWidthLimits.js'

const DRAG = readFileSync(
  new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')

const RIGHT_MIN = 240
const LEFT_MIN = 160

test('右侧面板：上限 + 编辑区下限不超过容器宽（容器不够时退回 min）', () => {
  for (const containerWidth of [320, 420, 440, 600, 930, 1090, 1350, 1870]) {
    const max = rightPanelMaxWidth(containerWidth, RIGHT_MIN)
    if (max + EDITOR_MIN_WIDTH > containerWidth) {
      // 唯一允许溢出的情形：容器连 min + 编辑区下限都装不下，此时只能退回 min
      assert.equal(max, RIGHT_MIN,
        `容器 ${containerWidth}：装不下时只允许退回 min，实得 ${max}`)
      assert.ok(containerWidth < RIGHT_MIN + EDITOR_MIN_WIDTH,
        `容器 ${containerWidth} 明明装得下，不该走兜底`)
    }
  }
})

test('左侧栏：rail + 左栏上限 + AI 面板 + 编辑区下限不超过整条主布局宽', () => {
  const rail = 50
  for (const layoutWidth of [1000, 1280, 1400, 1920]) {
    for (const aiPanelWidth of [0, 360, 700, 1050]) {
      const max = leftPanelMaxWidth(layoutWidth, rail, aiPanelWidth, LEFT_MIN)
      if (max === LEFT_MIN) continue
      assert.ok(rail + max + aiPanelWidth + EDITOR_MIN_WIDTH <= layoutWidth,
        `布局 ${layoutWidth} / AI ${aiPanelWidth}：左栏上限 ${max} 溢出`)
    }
  }
})

test('可用宽度不足时退回 min（宁可遮挡，也不给出负数/0 宽的上限）', () => {
  assert.equal(rightPanelMaxWidth(300, RIGHT_MIN), RIGHT_MIN)
  assert.equal(rightPanelMaxWidth(0, RIGHT_MIN), RIGHT_MIN)
  assert.equal(leftPanelMaxWidth(600, 50, 500, LEFT_MIN), LEFT_MIN)
  assert.equal(leftPanelMaxWidth(0, 0, 0, LEFT_MIN), LEFT_MIN)
})

test('病灶定点：1400px 窗口 + 420px 左栏，AI 面板上限不再是 1050', () => {
  // .workbench-main 实测宽 = 1400 − 50 rail − 420 左栏 = 930
  const container = 930
  const oldFormula = Math.max(RIGHT_MIN, Math.floor(1400 * 0.75)) // 现行公式
  assert.equal(oldFormula, 1050, '现行公式确实给出会溢出容器的 1050')
  const max = rightPanelMaxWidth(container, RIGHT_MIN)
  assert.ok(max <= 730, `新上限应 ≤ 730（930 − 200），实得 ${max}`)
  assert.ok(max < oldFormula)
})

test('非法/缺失入参不炸，且不会给出比 min 还小的上限', () => {
  assert.equal(rightPanelMaxWidth(undefined, RIGHT_MIN), RIGHT_MIN)
  assert.equal(rightPanelMaxWidth(NaN, RIGHT_MIN), RIGHT_MIN)
  assert.equal(rightPanelMaxWidth(-500, RIGHT_MIN), RIGHT_MIN)
  assert.equal(leftPanelMaxWidth(NaN, NaN, NaN, LEFT_MIN), LEFT_MIN)
})

test('接线：tabDragSplit 真的改用了这个模块，且两支都不再按整窗宽算', () => {
  assert.match(DRAG, /from\s+'\.\/panelWidthLimits\.js'/,
    'tabDragSplit.js 必须 import panelWidthLimits')
  assert.ok(!/vw\s*\*\s*0\.75/.test(DRAG),
    '左右两支的 `vw * 0.75` 都要换掉——只改右支的话，拖宽左栏能把同一个症状换个入口复现')
  assert.match(DRAG, /rightPanelMaxWidth\(/, '右支要用容器感知的上限')
  assert.match(DRAG, /leftPanelMaxWidth\(/, '左支要用容器感知的上限')
})

test('接线：容器几何在 startResize 里实测，不硬编码 rail 宽等常量', () => {
  const body = DRAG.slice(DRAG.indexOf('startResize(target, evt)'), DRAG.indexOf('onResizeMove(evt)'))
  assert.ok(body.length > 0, '截不到 startResize 函数体')
  assert.match(body, /parentElement/, '容器宽要从面板的父元素实测（边框、紧凑模式都会让常量漂）')
  assert.match(body, /clientWidth/, '要读实际渲染宽度')
  assert.match(body, /containerWidth/, '量到的宽度要缓存到 this.resizing 上给 applyResizeFrame 用')
})
