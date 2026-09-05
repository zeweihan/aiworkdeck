// Stage：场景脚本对着这一个对象写，不用直接碰 page/CDP 细节。
// 一幕 = 一个 async 函数，函数体里全是 stage.xxx() 调用（移动/点击/输入/等待/停顿）。

import { click, rightClick, moveCursorTo, typeText, sleep, titleCard } from './cursor.mjs'

export class Stage {
  constructor(page) {
    this.page = page
  }

  /** 等选择器出现（可见性不保证，跟 boundingBox 的 null 检查配合用）。 */
  async waitFor(selector, opts = {}) {
    return this.page.waitForSelector(selector, { timeout: 20000, ...opts })
  }

  async waitForText(text, { timeout = 20000 } = {}) {
    await this.page.waitForFunction(
      (t) => document.body && document.body.innerText.includes(t),
      { timeout },
      text
    )
  }

  /** 选择器命中的（第一个）元素中心点，供移动/点击用。 */
  async centerOf(selector) {
    const el = await this.waitFor(selector)
    const box = await el.boundingBox()
    if (!box) throw new Error(`选择器「${selector}」命中了元素但拿不到可视区域（可能被隐藏）`)
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
  }

  /** 选择器 + 文本包含匹配，用于没有稳定 class/id、只能按文案定位的按钮（上下文菜单项等）。 */
  async centerOfText(selector, text) {
    const box = await this.page.evaluate((sel, needle) => {
      const els = Array.from(document.querySelectorAll(sel))
      const hit = els.find((el) => (el.textContent || '').trim().includes(needle))
      if (!hit) return null
      const r = hit.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    }, selector, text)
    if (!box) throw new Error(`没找到「${selector}」里文本包含「${text}」的元素`)
    return box
  }

  /** 选择器命中的第 n 个元素（0 起），用于没有文案可辨认的一组同类元素（颜色色块等）。 */
  async centerOfNth(selector, index = 0) {
    const els = await this.page.$$(selector)
    const el = els[index]
    if (!el) throw new Error(`选择器「${selector}」第 ${index} 个元素不存在（共 ${els.length} 个）`)
    const box = await el.boundingBox()
    if (!box) throw new Error(`选择器「${selector}」第 ${index} 个元素拿不到可视区域`)
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
  }

  async moveTo(selector, opts) {
    const p = await this.centerOf(selector)
    await moveCursorTo(this.page, p.x, p.y, opts)
    return p
  }

  async moveToAt(x, y, opts) {
    await moveCursorTo(this.page, x, y, opts)
  }

  async click(selector, opts) {
    const p = await this.centerOf(selector)
    await click(this.page, p.x, p.y, opts)
  }

  async clickText(selector, text, opts) {
    const p = await this.centerOfText(selector, text)
    await click(this.page, p.x, p.y, opts)
  }

  async clickNth(selector, index, opts) {
    const p = await this.centerOfNth(selector, index)
    await click(this.page, p.x, p.y, opts)
  }

  async clickAt(x, y, opts) {
    await click(this.page, x, y, opts)
  }

  async rightClick(selector, opts) {
    const p = await this.centerOf(selector)
    await rightClick(this.page, p.x, p.y, opts)
  }

  async type(text, opts) {
    await typeText(this.page, text, opts)
  }

  async key(key) {
    await this.page.keyboard.press(key)
  }

  /** 单纯等待（技术性：等接口/渲染）。 */
  async wait(ms) {
    await sleep(ms)
  }

  /** 语义化别名：给旁白留白的静场，不是在等什么加载完成。 */
  async pause(ms) {
    await sleep(ms)
  }

  /** 章节小标题卡（淡入停留淡出），对应脚本里的「每幕开头 2 秒章节小标题卡」。 */
  async titleCard(text, opts) {
    await titleCard(this.page, text, opts)
  }
}
