// 虚拟光标：页面里注入一个跟随驱动事件走的光标元素，移动用缓动动画，点击有涟漪反馈。
// 观众要能看到「鼠标」在动，不是干净利落的瞬移。
//
// 设计取舍：真实鼠标坐标（page.mouse.move）与视觉光标元素在每一步插值里保持同一个
// (x,y)，不用两条独立时间线——否则点击时视觉光标位置和真实点击坐标可能对不上。

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function easeInOutCubic(t) {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}

// 在页面里建一个幂等的注入函数：光标元素 + 涟漪元素 + 一个 window 级更新函数，
// 避免每一步都重新查 DOM。evaluateOnNewDocument 保证 reLaunch/整页导航之后重新生效。
function injectCursorDom() {
  if (window.__awdSetCursor) return
  const style = document.createElement('style')
  style.textContent = `
    #awd-launch-cursor {
      position: fixed; left: 0; top: 0; width: 26px; height: 26px;
      margin-left: -2px; margin-top: -2px;
      pointer-events: none; z-index: 2147483647;
      transition: none; will-change: transform;
      filter: drop-shadow(0 1px 2px rgba(0,0,0,0.35));
    }
    #awd-launch-ripple {
      position: fixed; left: 0; top: 0; width: 36px; height: 36px;
      margin-left: -18px; margin-top: -18px;
      border-radius: 50%; pointer-events: none; z-index: 2147483646;
      border: 2px solid #5BD197; opacity: 0; transform: translate(-9999px,-9999px) scale(0.4);
    }
    @keyframes awd-launch-ripple-anim {
      0% { opacity: 0.9; transform: translate(var(--awd-rx), var(--awd-ry)) scale(0.4); }
      100% { opacity: 0; transform: translate(var(--awd-rx), var(--awd-ry)) scale(1.6); }
    }
    .awd-launch-ripple-play { animation: awd-launch-ripple-anim 420ms ease-out; }
    #awd-launch-title {
      position: fixed; left: 0; right: 0; top: 42%; text-align: center;
      pointer-events: none; z-index: 2147483645;
      font-family: "Songti SC", "STSong", serif; font-size: 40px; font-weight: 600;
      color: #1A1A1A; letter-spacing: 2px;
      text-shadow: 0 0 24px rgba(255,255,255,0.9), 0 0 48px rgba(255,255,255,0.9);
      opacity: 0; transition: opacity 500ms ease;
    }
  `
  document.head.appendChild(style)

  const title = document.createElement('div')
  title.id = 'awd-launch-title'

  const cursor = document.createElement('div')
  cursor.id = 'awd-launch-cursor'
  cursor.innerHTML = `
    <svg viewBox="0 0 24 24" width="26" height="26" xmlns="http://www.w3.org/2000/svg">
      <path d="M4 2 L4 20 L9 15.5 L12.5 22 L15.5 20.5 L12 14 L19 14 Z"
            fill="#1A1A1A" stroke="#FFFFFF" stroke-width="1.4" stroke-linejoin="round"/>
    </svg>`
  const ripple = document.createElement('div')
  ripple.id = 'awd-launch-ripple'

  const mount = () => {
    document.body.appendChild(cursor)
    document.body.appendChild(ripple)
    document.body.appendChild(title)
  }
  if (document.body) mount()
  else document.addEventListener('DOMContentLoaded', mount)

  window.__awdSetCursor = (x, y) => {
    cursor.style.transform = `translate(${x}px, ${y}px)`
  }
  window.__awdClickFlash = (x, y) => {
    ripple.style.setProperty('--awd-rx', `${x}px`)
    ripple.style.setProperty('--awd-ry', `${y}px`)
    ripple.classList.remove('awd-launch-ripple-play')
    // 强制 reflow 让同一个元素能连续重播动画
    void ripple.offsetWidth
    ripple.classList.add('awd-launch-ripple-play')
  }
  // 章节小标题卡：淡入、停留、淡出，纯覆盖层不影响页面布局。
  window.__awdShowTitle = (text) => {
    title.textContent = text
    title.style.opacity = '1'
  }
  window.__awdHideTitle = () => {
    title.style.opacity = '0'
  }
}

/** 注入光标 DOM，并让它在后续任何整页导航（reLaunch）之后自动重新出现。 */
export async function installCursor(page) {
  await page.evaluateOnNewDocument(injectCursorDom)
  await page.evaluate(injectCursorDom)
  const center = { x: page.viewport()?.width ? page.viewport().width / 2 : 960, y: 540 }
  page.__awdCursorPos = center
  await page.mouse.move(center.x, center.y)
  await page.evaluate((p) => window.__awdSetCursor && window.__awdSetCursor(p.x, p.y), center)
}

/** 导航之后光标 DOM 会随文档重建，脚本负责在下一步动作前补一次注入（幂等）。 */
async function ensureCursor(page) {
  await page.evaluate(injectCursorDom).catch(() => { /* 页面正在导航，下一步会重试 */ })
}

/** 从当前记录位置缓动移动到 (x, y)；真实鼠标坐标与视觉光标每一步同步。 */
export async function moveCursorTo(page, x, y, { duration = 550 } = {}) {
  await ensureCursor(page)
  const start = page.__awdCursorPos || { x, y }
  const dist = Math.hypot(x - start.x, y - start.y)
  if (dist < 1) { page.__awdCursorPos = { x, y }; return }
  const steps = Math.max(10, Math.min(60, Math.round(duration / 16)))
  for (let i = 1; i <= steps; i++) {
    const eased = easeInOutCubic(i / steps)
    const cx = start.x + (x - start.x) * eased
    const cy = start.y + (y - start.y) * eased
    await page.mouse.move(cx, cy)
    await page.evaluate((p) => window.__awdSetCursor && window.__awdSetCursor(p.x, p.y), { x: cx, y: cy })
    await sleep(duration / steps)
  }
  page.__awdCursorPos = { x, y }
}

async function flashClick(page, x, y) {
  await page.evaluate((p) => window.__awdClickFlash && window.__awdClickFlash(p.x, p.y), { x, y })
}

/** 移动到目标点，落一次左键点击，带涟漪反馈。 */
export async function click(page, x, y, { moveDuration = 550, holdMs = 90 } = {}) {
  await moveCursorTo(page, x, y, { duration: moveDuration })
  await flashClick(page, x, y)
  await page.mouse.down()
  await sleep(holdMs)
  await page.mouse.up()
}

/** 移动到目标点，落一次右键点击（打开上下文菜单），带涟漪反馈。 */
export async function rightClick(page, x, y, { moveDuration = 550, holdMs = 90 } = {}) {
  await moveCursorTo(page, x, y, { duration: moveDuration })
  await flashClick(page, x, y)
  await page.mouse.down({ button: 'right' })
  await sleep(holdMs)
  await page.mouse.up({ button: 'right' })
}

/** 逐字符打字，带自然延迟（不是整段瞬间贴上去）。 */
export async function typeText(page, text, { delay = 55 } = {}) {
  await page.keyboard.type(text, { delay })
}

/** 章节小标题卡：淡入 → 停留 holdMs → 淡出。纯覆盖层，不挡后续点击（pointer-events:none）。 */
export async function titleCard(page, text, { holdMs = 2000 } = {}) {
  await page.evaluate((t) => window.__awdShowTitle && window.__awdShowTitle(t), text)
  await sleep(holdMs)
  await page.evaluate(() => window.__awdHideTitle && window.__awdHideTitle())
  await sleep(500) // 等淡出动画走完再继续下一步，避免标题卡还叠在画面上
}

export { sleep }
