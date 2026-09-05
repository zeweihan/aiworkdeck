// 左右两侧面板的拖拽上限（dev-board#459）。
//
// 病灶：applyResizeFrame 原来按 `window.innerWidth * 0.75` 限宽，可是面板并不住在整窗
// 里——.side-panel-ai 的父容器 .workbench-main 只拿到「整窗 − rail − 左栏」。
// .side-panel{flex-shrink:0} 让面板不肯让宽，.workbench{overflow:hidden} 又把溢出裁掉：
// 编辑区先塌成 0，接着面板右半边被裁在窗口右缘之外（输入框/发送键看不见也点不着），
// 看上去就像窗口被撑出了屏幕。左栏那支是同一个缺陷的镜像。
//
// 修法是「按实测容器宽算」而不是「按整窗宽推算」：容器宽在 startResize 里从面板的
// 父元素量一次（见 tabDragSplit.js）。这里刻意不写 rail 宽、边框宽这类常量——边框、
// 紧凑模式、右侧 dock（dev-board#180）都会让常量漂。
//
// 保留的既有取向：只保证编辑区还剩 EDITOR_MIN_WIDTH，不做「窗口变窄时回夹面板」
// （project-overview.vue handleResponsiveResize 那句「遮挡就遮挡」是既有产品决策）。
//
// 零依赖纯函数、只收数值参数，好在 node:test 里真跑一遍（同目录 flushDirtyEditors.js
// 的先例：本目录其余模块都 import 了 @/ 别名，node 直接 import 不动）。

// 编辑区最小可用宽度。定得小是刻意的：多数用户从没撞上这个上限，
// 定大了等于悄悄改掉他们本来能拖到的范围。
export const EDITOR_MIN_WIDTH = 200

const px = (v) => {
  const n = Math.floor(Number(v))
  return Number.isFinite(n) && n > 0 ? n : 0
}

/**
 * 右侧 AI 面板的拖拽上限。
 * @param {number} containerWidth 面板父容器（.workbench-main）的实测 clientWidth
 * @param {number} min            面板自身的最小宽（不足时的兜底返回值）
 */
export function rightPanelMaxWidth(containerWidth, min = 240) {
  const floor = px(min)
  return Math.max(floor, px(containerWidth) - EDITOR_MIN_WIDTH)
}

/**
 * 左栏的拖拽上限：整条主布局宽里还坐着 rail 与右侧 AI 面板。
 * @param {number} layoutWidth   左栏父容器（.main-layout）的实测 clientWidth
 * @param {number} railWidth     rail 的实测宽（隐藏/不存在时传 0）
 * @param {number} aiPanelWidth  右侧面板当前实测宽（收起时传 0）
 * @param {number} min           左栏自身的最小宽
 */
export function leftPanelMaxWidth(layoutWidth, railWidth, aiPanelWidth, min = 160) {
  const floor = px(min)
  const avail = px(layoutWidth) - px(railWidth) - px(aiPanelWidth)
  return Math.max(floor, avail - EDITOR_MIN_WIDTH)
}
