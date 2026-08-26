/**
 * 动效助手（anime.js v4，dev-board#176）。
 *
 * 原则：动效必须有动机——浮现表达「新内容到达」、面板升起表达「层级出现」，
 * 不做装饰性循环动画。prefers-reduced-motion 时全部退化为直接显示。
 * 时长压在 150-320ms，只动 transform/opacity（不触发布局）。
 */
import { animate, stagger } from 'animejs'

export function reducedMotion() {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch (e) {
    return false
  }
}

/** 消息气泡/卡片浮现：轻微上移 + 淡入 */
export function riseIn(el) {
  if (!el || reducedMotion()) return
  animate(el, {
    opacity: [0, 1],
    translateY: [10, 0],
    duration: 280,
    ease: 'outCubic'
  })
}

/** 覆盖层面板从底部升起 */
export function panelUp(el) {
  if (!el || reducedMotion()) return
  animate(el, {
    opacity: [0, 1],
    translateY: [24, 0],
    duration: 260,
    ease: 'outCubic'
  })
}

/** 小型菜单弹出（头像菜单/更多菜单）：缩放 + 淡入 */
export function popIn(el) {
  if (!el || reducedMotion()) return
  animate(el, {
    opacity: [0, 1],
    scale: [0.94, 1],
    translateY: [-4, 0],
    duration: 180,
    ease: 'outQuad'
  })
}

/** 空态快捷入口逐个浮现 */
export function staggerIn(els) {
  if (!els || !els.length || reducedMotion()) return
  animate(els, {
    opacity: [0, 1],
    translateY: [12, 0],
    duration: 320,
    delay: stagger(70),
    ease: 'outCubic'
  })
}
